package io.devportal.asset;

import io.devportal.asset.dto.AddDependencyRequest;
import io.devportal.asset.dto.AssetGraphView;
import io.devportal.asset.dto.AssetView;
import io.devportal.asset.dto.CreateAssetRequest;
import io.devportal.asset.dto.DependencyView;
import io.devportal.asset.dto.RegisterFromGitHubRequest;
import io.devportal.asset.dto.UpdateAssetRequest;
import io.devportal.asset.error.ConflictException;
import io.devportal.asset.error.NotFoundException;
import io.devportal.github.GitHubClient;
import io.devportal.github.GitHubFileContent;
import io.devportal.github.GitHubRepoSummary;
import io.devportal.manifest.Manifest;
import io.devportal.manifest.ManifestParseResult;
import io.devportal.manifest.ManifestParser;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssetService {

    private static final Logger log = LoggerFactory.getLogger(AssetService.class);

    private final AssetRepository repo;
    private final GitHubClient github;
    private final ManifestParser manifestParser;

    public AssetService(AssetRepository repo, GitHubClient github, ManifestParser manifestParser) {
        this.repo = repo;
        this.github = github;
        this.manifestParser = manifestParser;
    }

    public List<AssetView> list(String query, String type, String lifecycle) {
        return list(query, type, lifecycle, null);
    }

    public List<AssetView> list(String query, String type, String lifecycle, Boolean favorite) {
        return repo.findAll(query, type, lifecycle, favorite).stream().map(AssetView::of).toList();
    }

    public AssetView get(String id) {
        return AssetView.of(loadOr404(id));
    }

    @Transactional
    public AssetView create(CreateAssetRequest r) {
        if (repo.existsById(r.id())) {
            throw new ConflictException("Asset '" + r.id() + "' already exists");
        }
        Asset a = new Asset(
            r.id(), r.name(), r.description(), r.owner(),
            r.type(), r.language(), r.repoUrl(),
            r.repoDefaultBranch() == null ? "main" : r.repoDefaultBranch(),
            r.tags() == null ? List.of() : r.tags(),
            r.lifecycle() == null ? "experimental" : r.lifecycle(),
            r.id(),     // default namespace = asset id
            false, null, false,
            null, null
        );
        repo.insert(a);
        return AssetView.of(repo.findById(r.id()).orElseThrow());
    }

    @Transactional
    public AssetView update(String id, UpdateAssetRequest r) {
        Asset existing = loadOr404(id);
        Integer mergedRating = r.rating() != null
            ? (r.rating() == 0 ? null : r.rating())   // 0 from client = clear rating
            : existing.rating();
        Asset merged = new Asset(
            existing.id(),
            r.name() != null ? r.name() : existing.name(),
            r.description() != null ? r.description() : existing.description(),
            r.owner() != null ? r.owner() : existing.owner(),
            r.type() != null ? r.type() : existing.type(),
            r.language() != null ? r.language() : existing.language(),
            r.repoUrl() != null ? r.repoUrl() : existing.repoUrl(),
            r.repoDefaultBranch() != null ? r.repoDefaultBranch() : existing.repoDefaultBranch(),
            r.tags() != null ? r.tags() : existing.tags(),
            r.lifecycle() != null ? r.lifecycle() : existing.lifecycle(),
            r.k8sNamespace() != null ? r.k8sNamespace() : existing.k8sNamespace(),
            r.favorite() != null ? r.favorite() : existing.favorite(),
            mergedRating,
            r.dashboardPinned() != null ? r.dashboardPinned() : existing.dashboardPinned(),
            existing.createdAt(),
            Instant.now()
        );
        repo.update(merged);
        return AssetView.of(repo.findById(id).orElseThrow());
    }

    @Transactional
    public void delete(String id) {
        if (repo.delete(id) == 0) {
            throw new NotFoundException("Asset '" + id + "' not found");
        }
    }

    public List<DependencyView> dependenciesOf(String id) {
        loadOr404(id);
        return repo.findDependenciesOf(id).stream().map(DependencyView::of).toList();
    }

    public List<DependencyView> consumersOf(String id) {
        loadOr404(id);
        return repo.findConsumersOf(id).stream().map(DependencyView::of).toList();
    }

    @Transactional
    public DependencyView addDependency(String consumerId, AddDependencyRequest r) {
        loadOr404(consumerId);
        if (consumerId.equals(r.producerId())) {
            throw new ConflictException("An asset cannot depend on itself");
        }
        if (!repo.existsById(r.producerId())) {
            throw new NotFoundException("Producer asset '" + r.producerId() + "' not found");
        }
        return DependencyView.of(repo.insertDependency(consumerId, r.producerId(),
            r.versionRef(), r.kind() == null ? "build" : r.kind()));
    }

    @Transactional
    public void removeDependency(String consumerId, String producerId, String kind) {
        if (repo.deleteDependency(consumerId, producerId, kind == null ? "build" : kind) == 0) {
            throw new NotFoundException("Dependency edge not found");
        }
    }

    /** Backwards-compatible default: deep producers, 1-hop consumers. */
    public AssetGraphView graph(String rootId) {
        return graph(rootId, "both", -1, 1);
    }

    /**
     * Reachable graph from {@code rootId}.
     *
     * @param direction "producers" (what root depends on, transitively),
     *                  "consumers" (who depends on root, transitively),
     *                  "both"      (default — both directions).
     * @param producerDepth max producer hops to traverse; -1 = unlimited. 0 = root only.
     * @param consumerDepth max consumer hops; -1 = unlimited. 0 = no consumers shown.
     */
    public AssetGraphView graph(String rootId, String direction, int producerDepth, int consumerDepth) {
        loadOr404(rootId);
        boolean wantProducers = !"consumers".equalsIgnoreCase(direction);
        boolean wantConsumers = !"producers".equalsIgnoreCase(direction);

        Map<String, Asset> nodes = new LinkedHashMap<>();
        List<DependencyView> edges = new ArrayList<>();
        repo.findById(rootId).ifPresent(a -> nodes.put(a.id(), a));

        if (wantProducers) walk(rootId, producerDepth, true, nodes, edges);
        if (wantConsumers) walk(rootId, consumerDepth, false, nodes, edges);

        return new AssetGraphView(
            rootId,
            nodes.values().stream().map(AssetView::of).toList(),
            edges
        );
    }

    /** BFS in one direction. {@code producers=true} walks A→produces, false walks consumers↑. */
    private void walk(String rootId, int maxDepth, boolean producers,
                      Map<String, Asset> nodes, List<DependencyView> edges) {
        Set<String> visited = new HashSet<>();
        visited.add(rootId);
        Deque<String> frontier = new ArrayDeque<>();
        frontier.add(rootId);
        int depth = 0;
        while (!frontier.isEmpty() && (maxDepth < 0 || depth < maxDepth)) {
            int n = frontier.size();
            for (int i = 0; i < n; i++) {
                String cur = frontier.poll();
                List<Dependency> nextEdges = producers
                    ? repo.findDependenciesOf(cur)
                    : repo.findConsumersOf(cur);
                for (Dependency d : nextEdges) {
                    edges.add(DependencyView.of(d));
                    String next = producers ? d.producerId() : d.consumerId();
                    if (visited.add(next)) {
                        repo.findById(next).ifPresent(a -> nodes.putIfAbsent(a.id(), a));
                        frontier.add(next);
                    }
                }
            }
            depth++;
        }
    }

    /** Register a GitHub repo, picking up the devportal.yaml if it has one. */
    @Transactional
    public AssetView registerFromGitHub(RegisterFromGitHubRequest r) throws IOException {
        GitHubRepoSummary summary = github.getRepo(r.fullName());
        Optional<GitHubFileContent> manifestFile = github.getManifest(r.fullName(), summary.defaultBranch());

        String id = r.overrideId();
        String name = summary.name();
        String description = summary.description();
        String owner = summary.owner();
        String type = "library";
        String language = null;
        List<String> tags = summary.topics() == null ? List.of() : summary.topics();
        String lifecycle = summary.archived() ? "deprecated" : "experimental";

        if (manifestFile.isPresent()) {
            ManifestParseResult parse = manifestParser.parse(manifestFile.get().content());
            if (!parse.valid()) {
                log.warn("devportal.yaml in {} failed validation: {}", r.fullName(), parse.errors());
            }
            if (parse.manifest() != null) {
                Manifest m = parse.manifest();
                if (id == null && m.metadata() != null && m.metadata().id() != null) id = m.metadata().id();
                if (m.metadata() != null) {
                    if (m.metadata().name() != null) name = m.metadata().name();
                    if (m.metadata().description() != null) description = m.metadata().description();
                    if (m.metadata().owner() != null) owner = m.metadata().owner();
                    if (m.metadata().tags() != null && !m.metadata().tags().isEmpty()) tags = m.metadata().tags();
                }
                if (m.spec() != null) {
                    if (m.spec().type() != null) type = m.spec().type();
                    if (m.spec().language() != null) language = m.spec().language();
                }
            }
        }

        if (id == null) id = defaultIdFromRepo(summary);
        if (repo.existsById(id)) {
            throw new ConflictException("Asset '" + id + "' already exists");
        }

        Asset a = new Asset(id, name, description, owner, type, language,
            summary.htmlUrl(), summary.defaultBranch(),
            tags, lifecycle, id /* default namespace = asset id */,
            false, null, false,
            null, null);
        repo.insert(a);
        return AssetView.of(repo.findById(id).orElseThrow());
    }

    private static String defaultIdFromRepo(GitHubRepoSummary s) {
        return s.name().toLowerCase().replaceAll("[^a-z0-9-]", "-");
    }

    private Asset loadOr404(String id) {
        return repo.findById(id).orElseThrow(
            () -> new NotFoundException("Asset '" + id + "' not found"));
    }
}
