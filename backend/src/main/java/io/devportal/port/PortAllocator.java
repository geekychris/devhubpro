package io.devportal.port;

import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.ConflictException;
import io.devportal.asset.error.NotFoundException;
import io.devportal.manifest.Manifest;
import io.devportal.manifest.ManifestParseResult;
import io.devportal.manifest.ManifestParser;
import io.devportal.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Allocates concrete ports for an asset's named slots from the configured pools. */
@Service
@EnableConfigurationProperties(PortProperties.class)
public class PortAllocator {

    private static final Logger log = LoggerFactory.getLogger(PortAllocator.class);

    private final PortProperties props;
    private final PortRepository repo;
    private final AssetRepository assets;
    private final WorkspaceService workspace;
    private final ManifestParser manifestParser;
    private final PortSlotInferrer inferrer;

    public PortAllocator(PortProperties props, PortRepository repo,
                         AssetRepository assets, WorkspaceService workspace,
                         ManifestParser manifestParser, PortSlotInferrer inferrer) {
        this.props = props;
        this.repo = repo;
        this.assets = assets;
        this.workspace = workspace;
        this.manifestParser = manifestParser;
        this.inferrer = inferrer;
    }

    public List<PortReservation> listAll() { return repo.findAll(); }

    public List<PortReservation> listFor(String assetId) {
        if (!assets.existsById(assetId)) throw new NotFoundException("Asset '" + assetId + "' not found");
        return repo.findByAsset(assetId);
    }

    /**
     * Allocate ports for every slot declared in the asset's devportal.yaml. Idempotent: if
     * reservations already exist for (asset, scope), returns them as-is unless {@code reallocate} is true.
     */
    @Transactional
    public List<PortReservation> allocate(String assetId, String scope, boolean reallocate) throws IOException {
        if (!assets.existsById(assetId)) throw new NotFoundException("Asset '" + assetId + "' not found");
        validateScope(scope);

        List<PortReservation> existing = repo.findByAssetAndScope(assetId, scope);
        if (!existing.isEmpty() && !reallocate) return existing;
        if (reallocate) repo.deleteByAssetAndScope(assetId, scope);

        // Auto-clone if the workspace isn't there yet — saves a "run a build first" round-trip.
        Path ws = workspace.workspaceFor(assetId);
        if (!Files.isDirectory(ws.resolve(".git"))) {
            io.devportal.asset.Asset asset = assets.findById(assetId).orElseThrow();
            try {
                workspace.syncCheckout(assetId, asset.repoUrl(), asset.repoDefaultBranch());
            } catch (org.eclipse.jgit.api.errors.GitAPIException e) {
                throw new ConflictException("Could not clone " + asset.repoUrl() + ": " + e.getMessage());
            }
        }

        List<Manifest.Port> slots = readSlotsFromManifest(assetId);
        if (slots.isEmpty()) {
            // Manifest absent or has no ports declared — infer from real signals (Spring config, Dockerfile, k8s).
            slots = inferrer.infer(ws);
            log.info("Inferred {} port slot(s) for {} ({})", slots.size(), assetId,
                slots.stream().map(Manifest.Port::name).toList());
        }
        if (slots.isEmpty()) {
            throw new ConflictException(
                "No port slots could be inferred for '" + assetId + "' — declare them in devportal.yaml's "
                + "spec.runtime.ports, or ensure the workspace has a Dockerfile / Spring config / k8s manifests.");
        }

        PortProperties.Range range = scopeRange(scope);
        List<PortReservation> result = new ArrayList<>();
        for (Manifest.Port slot : slots) {
            String protocol = slot.protocol() == null ? "tcp" : slot.protocol();
            Set<Integer> taken = repo.portsTakenInScope(scope, protocol);
            int port = pickFreePort(range, taken);
            taken.add(port);
            PortReservation r = repo.insert(assetId, slot.name(), scope, port, protocol);
            log.info("Allocated {}/{} = {} for asset {} ({})", scope, slot.name(), port, assetId, protocol);
            result.add(r);
        }
        return result;
    }

    @Transactional
    public int release(String assetId, String scope) {
        validateScope(scope);
        if (!assets.existsById(assetId)) throw new NotFoundException("Asset '" + assetId + "' not found");
        return repo.deleteByAssetAndScope(assetId, scope);
    }

    private List<Manifest.Port> readSlotsFromManifest(String assetId) throws IOException {
        Path ws = workspace.workspaceFor(assetId);
        Path manifest = ws.resolve("devportal.yaml");
        if (!Files.exists(manifest)) return List.of();
        ManifestParseResult parsed = manifestParser.parse(Files.readString(manifest));
        if (parsed.manifest() == null || parsed.manifest().spec() == null
            || parsed.manifest().spec().runtime() == null
            || parsed.manifest().spec().runtime().ports() == null) {
            return List.of();
        }
        return parsed.manifest().spec().runtime().ports();
    }

    private PortProperties.Range scopeRange(String scope) {
        return switch (scope) {
            case "local" -> props.local();
            case "k8s-nodeport" -> props.k8sNodeport();
            default -> throw new IllegalArgumentException("Unknown scope: " + scope);
        };
    }

    private static void validateScope(String scope) {
        if (!"local".equals(scope) && !"k8s-nodeport".equals(scope)) {
            throw new IllegalArgumentException("Scope must be 'local' or 'k8s-nodeport'");
        }
    }

    private static int pickFreePort(PortProperties.Range range, Set<Integer> taken) {
        for (int p = range.start(); p <= range.end(); p++) {
            if (!taken.contains(p)) return p;
        }
        throw new IllegalStateException(
            "Port pool exhausted for range " + range.start() + "-" + range.end());
    }
}
