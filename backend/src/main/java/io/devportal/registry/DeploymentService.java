package io.devportal.registry;

import io.devportal.asset.Asset;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.BadRequestException;
import io.devportal.asset.error.NotFoundException;
import io.devportal.registry.DeploymentRepository.Filter;
import io.devportal.registry.DeploymentRepository.Upsert;
import io.devportal.registry.RegistryDtos.AssetHint;
import io.devportal.registry.RegistryDtos.DeploymentReport;
import io.devportal.registry.RegistryDtos.ReportRequest;
import io.devportal.registry.RegistryDtos.ReportResult;
import io.devportal.registry.RegistryDtos.TargetSummary;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeploymentService {

    private static final Logger log = LoggerFactory.getLogger(DeploymentService.class);
    static final int MAX_BATCH = 1000;
    static final String AUTO_TAG = "registry";

    private final DeploymentRepository repo;
    private final AssetRepository assets;

    public DeploymentService(DeploymentRepository repo, AssetRepository assets) {
        this.repo = repo;
        this.assets = assets;
    }

    public List<Deployment> list(Filter f) {
        return repo.find(f);
    }

    public Deployment get(long id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Deployment " + id + " not found"));
    }

    public List<TargetSummary> targets() {
        return repo.targets();
    }

    public List<Deployment> forAsset(String assetId, boolean includeRetired) {
        if (!assets.existsById(assetId)) throw new NotFoundException("Asset '" + assetId + "' not found");
        return repo.find(new Filter(null, assetId, null, null, null, null, includeRetired));
    }

    @Transactional
    public Deployment retire(long id) {
        get(id);
        repo.retire(id);
        return get(id);
    }

    public void recordCheck(long id, String status, String error, Integer latencyMs) {
        repo.recordCheck(id, status, error, latencyMs);
    }

    @Transactional
    public void delete(long id) {
        if (repo.delete(id) == 0) throw new NotFoundException("Deployment " + id + " not found");
    }

    /** Idempotent batch upsert (+ optional source-scoped reconcile). */
    @Transactional
    public ReportResult report(ReportRequest req) {
        if (req == null) throw new BadRequestException("body required");
        String batchSource = Deployments.blankToNull(req.source());
        List<DeploymentReport> items = req.deployments() == null ? List.of() : req.deployments();
        if (items.size() > MAX_BATCH) throw new BadRequestException("at most " + MAX_BATCH + " deployments per report");
        boolean reconcile = Boolean.TRUE.equals(req.reconcile());
        if (reconcile && batchSource == null) throw new BadRequestException("reconcile needs a source");

        int created = 0, updated = 0;
        List<String> assetsCreated = new ArrayList<>();
        Set<Long> kept = new HashSet<>();
        Set<String> seenKeys = new HashSet<>();
        List<Long> touched = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            DeploymentReport item = items.get(i);
            if (item == null) throw new BadRequestException("deployments[" + i + "] is null");
            String assetId = resolveAsset(item, i, assetsCreated);
            Upsert u = normalize(item, assetId, batchSource, i);
            if (!seenKeys.add(Deployments.key(u.assetId(), u.target(), u.namespace(), u.name()))) {
                throw new BadRequestException("deployments[" + i + "] repeats key "
                    + Deployments.key(u.assetId(), u.target(), u.namespace(), u.name()));
            }
            Optional<Deployment> existing = repo.findByKey(u.assetId(), u.target(), u.namespace(), u.name());
            String probe = Deployments.probeUrl(u.healthUrl(), u.phase(), u.urls());
            Decision d = decideStatus(existing.orElse(null), probe, u.reportedStatus());
            Upsert withStatus = withStatus(u, d.status());
            long id;
            if (existing.isPresent()) {
                id = existing.get().id();
                repo.update(id, withStatus, d.resetCheck());
                updated++;
            } else {
                id = repo.insert(withStatus);
                created++;
            }
            kept.add(id);
            touched.add(id);
        }

        int retired = 0;
        if (reconcile) {
            Set<String> targets = req.reconcileTargets() == null ? Set.of() : new HashSet<>(req.reconcileTargets());
            for (long id : toRetire(repo.findLiveBySource(batchSource), kept, targets)) {
                retired += repo.retire(id);
            }
        }
        List<Deployment> out = new ArrayList<>(touched.size());
        for (long id : touched) repo.findById(id).ifPresent(out::add);
        if (created + updated + retired > 0) {
            log.debug("registry report source={} created={} updated={} retired={} assetsCreated={}",
                batchSource, created, updated, retired, assetsCreated);
        }
        return new ReportResult(created, updated, retired, assetsCreated, out);
    }

    /** Finds the asset a report refers to, creating a minimal one when it is new. */
    private String resolveAsset(DeploymentReport item, int i, List<String> assetsCreated) {
        AssetHint hint = item.asset();
        String id = Deployments.blankToNull(item.assetId());
        if (id == null && hint != null) {
            id = Deployments.slugify(hint.name());
            if (id == null) id = Deployments.slugify(hint.repoUrl());
        }
        if (id == null) throw new BadRequestException("deployments[" + i + "]: assetId or asset.name/repoUrl required");
        if (!Deployments.validAssetId(id)) {
            throw new BadRequestException("deployments[" + i + "]: assetId '" + id
                + "' must match ^[a-z][a-z0-9-]{1,62}[a-z0-9]$");
        }
        if (assets.existsById(id)) return id;

        String name = hint != null && Deployments.blankToNull(hint.name()) != null ? hint.name().trim() : id;
        String type = hint != null && hint.type() != null ? hint.type().trim() : "service";
        if (!Deployments.ASSET_TYPES.contains(type)) {
            throw new BadRequestException("deployments[" + i + "]: asset.type must be one of " + Deployments.ASSET_TYPES);
        }
        Set<String> tags = new LinkedHashSet<>();
        if (hint != null && hint.tags() != null) {
            for (String t : hint.tags()) if (t != null && !t.isBlank()) tags.add(t.trim());
        }
        tags.add(AUTO_TAG);
        String repoUrl = hint != null && hint.repoUrl() != null ? hint.repoUrl().trim() : "";
        String description = hint != null ? Deployments.blankToNull(hint.description()) : null;
        assets.insert(new Asset(id, name, description, null, type, null, repoUrl, "main",
            List.copyOf(tags), "experimental", id, false, null, false, null, null));
        assetsCreated.add(id);
        return id;
    }

    /** Validates one report item and turns it into row values (status filled in later). */
    static Upsert normalize(DeploymentReport r, String assetId, String batchSource, int i) {
        String where = "deployments[" + i + "]";
        String target = Deployments.blankToNull(r.target());
        if (target == null) throw new BadRequestException(where + ": target required");
        String kind = Deployments.blankToNull(r.targetKind());
        kind = kind == null ? "other" : kind.toLowerCase();
        if (!Deployments.TARGET_KINDS.contains(kind)) {
            throw new BadRequestException(where + ": targetKind must be one of " + Deployments.TARGET_KINDS);
        }
        String phase = Deployments.blankToNull(r.phase());
        phase = phase == null ? "running" : phase.toLowerCase();
        if (!Deployments.PHASES.contains(phase)) {
            throw new BadRequestException(where + ": phase must be one of " + Deployments.PHASES);
        }
        String status = Deployments.blankToNull(r.status());
        if (status != null) {
            status = status.toLowerCase();
            if (!Deployments.REPORTABLE_STATUSES.contains(status)) {
                throw new BadRequestException(where + ": status must be one of " + Deployments.REPORTABLE_STATUSES);
            }
        }
        String source = Deployments.blankToNull(r.source());
        if (source == null) source = batchSource;
        if (source == null) source = "manual";

        List<DeploymentUrl> urls = new ArrayList<>();
        if (r.urls() != null) {
            for (DeploymentUrl u : r.urls()) {
                if (u == null || Deployments.blankToNull(u.url()) == null) continue;
                String label = Deployments.blankToNull(u.label());
                urls.add(new DeploymentUrl(label == null ? "link" : label, u.url().trim(), u.primary()));
            }
        }
        Map<String, String> labels = new LinkedHashMap<>();
        if (r.labels() != null) {
            r.labels().forEach((k, v) -> { if (k != null && !k.isBlank() && v != null) labels.put(k.trim(), v); });
        }
        return new Upsert(assetId, target, kind, Deployments.nz(r.namespace()), Deployments.nz(r.name()),
            Deployments.blankToNull(r.version()), Deployments.blankToNull(r.commit()), Deployments.blankToNull(r.ref()),
            phase, urls, Deployments.blankToNull(r.healthUrl()), source, labels, Deployments.blankToNull(r.notes()),
            null, status);
    }

    record Decision(String status, boolean resetCheck) {}

    /**
     * Effective status after a report. With something to probe, the poller owns the status: a new
     * or revived row starts 'unknown' until the first check, an existing one keeps its last result.
     * With nothing to probe, the reporter's word (or 'reported') is the status.
     */
    static Decision decideStatus(Deployment existing, String probeUrl, String reportedStatus) {
        if (probeUrl == null) {
            return new Decision(reportedStatus == null ? "reported" : reportedStatus, true);
        }
        if (existing == null) return new Decision("unknown", true);
        boolean stale = existing.retiredAt() != null
            || "retired".equals(existing.status())
            || "reported".equals(existing.status())
            || !probeUrl.equals(existing.probeUrl());
        return stale ? new Decision("unknown", true) : new Decision(existing.status(), false);
    }

    /** Live deployments of the source not in the batch (and, when targets are given, on one of them). */
    static List<Long> toRetire(List<Deployment> live, Set<Long> kept, Set<String> targets) {
        List<Long> out = new ArrayList<>();
        for (Deployment d : live) {
            if (kept.contains(d.id())) continue;
            if (!targets.isEmpty() && !targets.contains(d.target())) continue;
            out.add(d.id());
        }
        return out;
    }

    private static Upsert withStatus(Upsert u, String status) {
        return new Upsert(u.assetId(), u.target(), u.targetKind(), u.namespace(), u.name(), u.version(),
            u.commit(), u.ref(), u.phase(), u.urls(), u.healthUrl(), u.source(), u.labels(), u.notes(),
            status, u.reportedStatus());
    }
}
