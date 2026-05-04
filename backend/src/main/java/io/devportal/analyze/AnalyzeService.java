package io.devportal.analyze;

import io.devportal.analyze.dto.AnalyzeReport;
import io.devportal.analyze.dto.AutoWireResult;
import io.devportal.analyze.dto.MavenAnalysis;
import io.devportal.analyze.dto.MavenCoord;
import io.devportal.asset.Asset;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.Dependency;
import io.devportal.asset.error.ConflictException;
import io.devportal.asset.error.NotFoundException;
import io.devportal.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates Maven analysis: parses pom.xml, persists this asset's artifacts, matches deps. */
@Service
public class AnalyzeService {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeService.class);

    private final AssetRepository assets;
    private final AssetArtifactRepository artifacts;
    private final WorkspaceService workspace;
    private final MavenAnalyzer maven;

    public AnalyzeService(AssetRepository assets, AssetArtifactRepository artifacts,
                          WorkspaceService workspace, MavenAnalyzer maven) {
        this.assets = assets;
        this.artifacts = artifacts;
        this.workspace = workspace;
        this.maven = maven;
    }

    @Transactional
    public AnalyzeReport analyze(String assetId) throws IOException {
        Asset asset = assets.findById(assetId).orElseThrow(
            () -> new NotFoundException("Asset '" + assetId + "' not found"));
        Path ws = workspace.workspaceFor(asset.id());
        if (!Files.isDirectory(ws.resolve(".git"))) {
            // Auto-clone — much friendlier than asking the user to "run a build first".
            try {
                workspace.syncCheckout(asset.id(), asset.repoUrl(), asset.repoDefaultBranch());
            } catch (org.eclipse.jgit.api.errors.GitAPIException e) {
                throw new ConflictException("Could not clone " + asset.repoUrl() + ": " + e.getMessage());
            }
        }
        if (!maven.hasPom(ws)) {
            return new AnalyzeReport(assetId, "maven", false, List.of(), List.of(),
                List.of("No pom.xml at workspace root — Maven analyzer skipped"));
        }

        MavenAnalysis result = maven.analyze(ws);

        // Refresh this asset's published artifact records.
        artifacts.deleteForAsset(assetId, "maven");
        for (MavenCoord c : result.publishedArtifacts()) {
            artifacts.upsert(assetId, "maven", c.groupId(), c.artifactId(), c.version(), c.relativePath());
        }

        // Match each declared dep against the global registry.
        Set<String> existingProducerIds = new HashSet<>();
        for (Dependency d : assets.findDependenciesOf(assetId)) {
            existingProducerIds.add(d.producerId());
        }

        List<AnalyzeReport.DependencyMatch> matches = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MavenCoord d : result.declaredDependencies()) {
            if (!seen.add(d.key())) continue;
            Optional<AssetArtifact> producer = artifacts.findProducer("maven", d.groupId(), d.artifactId());
            if (producer.isPresent() && !producer.get().assetId().equals(assetId)) {
                AssetArtifact a = producer.get();
                matches.add(new AnalyzeReport.DependencyMatch(
                    d, a.assetId(), a.relativePath(),
                    existingProducerIds.contains(a.assetId())));
            } else {
                matches.add(new AnalyzeReport.DependencyMatch(d, null, null, false));
            }
        }

        log.info("Analyzed {}: published={} deps={} matched={}", assetId,
            result.publishedArtifacts().size(),
            result.declaredDependencies().size(),
            matches.stream().filter(m -> m.matchedAssetId() != null).count());
        return new AnalyzeReport(assetId, "maven", true,
            result.publishedArtifacts(), matches, result.warnings());
    }

    @Transactional
    public AutoWireResult autoWire(String assetId) throws IOException {
        AnalyzeReport report = analyze(assetId);
        int added = 0;
        int already = 0;
        int unmatched = 0;
        List<String> wired = new ArrayList<>();
        Set<String> dedupe = new HashSet<>();

        for (AnalyzeReport.DependencyMatch m : report.dependencyMatches()) {
            if (m.matchedAssetId() == null) { unmatched++; continue; }
            if (!dedupe.add(m.matchedAssetId())) continue;
            if (m.alreadyWired()) { already++; continue; }
            assets.insertDependency(assetId, m.matchedAssetId(),
                m.coord().version() == null ? "main" : m.coord().version(), "build");
            wired.add(m.matchedAssetId());
            added++;
        }
        return new AutoWireResult(assetId, added, already, unmatched, wired);
    }

    public List<AssetArtifact> listArtifacts(String assetId) {
        if (!assets.existsById(assetId)) {
            throw new NotFoundException("Asset '" + assetId + "' not found");
        }
        return artifacts.findByAsset(assetId);
    }
}
