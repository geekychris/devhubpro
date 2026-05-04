package io.devportal.enrich;

import io.devportal.analyze.GitHubUrlParser;
import io.devportal.asset.Asset;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.NotFoundException;
import io.devportal.enrich.dto.GitInfo;
import io.devportal.github.GitHubClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.kohsuke.github.GHLicense;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Pulls extra GitHub-side metadata for an asset's overview view: tags, license, stars, etc.
 *
 * <p>Kept separate from {@link io.devportal.github.GitHubClient} so the existing client stays focused.
 */
@Service
public class EnrichService {

    private static final Logger log = LoggerFactory.getLogger(EnrichService.class);
    private static final int MAX_TAGS = 20;

    private final AssetRepository assets;
    private final GitHubClient github;

    public EnrichService(AssetRepository assets, GitHubClient github) {
        this.assets = assets;
        this.github = github;
    }

    public GitInfo gitInfo(String assetId) throws IOException {
        Asset asset = assets.findById(assetId).orElseThrow(
            () -> new NotFoundException("Asset '" + assetId + "' not found"));
        String fullName = GitHubUrlParser.fullName(asset.repoUrl());
        if (fullName == null) {
            throw new io.devportal.asset.error.ConflictException(
                "Asset '" + assetId + "' has no recognizable GitHub URL");
        }
        GHRepository repo = invokeRepo(fullName);

        List<GitInfo.Tag> tags = new ArrayList<>();
        try {
            int n = 0;
            for (GHTag t : repo.listTags().withPageSize(MAX_TAGS)) {
                tags.add(new GitInfo.Tag(t.getName(), t.getCommit() == null ? null : t.getCommit().getSHA1()));
                if (++n >= MAX_TAGS) break;
            }
        } catch (Exception e) {
            log.warn("Failed listing tags for {}: {}", fullName, e.getMessage());
        }

        String license = null;
        try {
            GHLicense l = repo.getLicense();
            if (l != null) license = l.getName();
        } catch (Exception ignored) {}

        return new GitInfo(
            repo.getFullName(),
            repo.getDescription(),
            repo.getDefaultBranch(),
            repo.getHomepage(),
            license,
            repo.getStargazersCount(),
            repo.getForksCount(),
            repo.getOpenIssueCount(),
            repo.getPushedAt() == null ? null : repo.getPushedAt().toInstant(),
            repo.getUpdatedAt() == null ? null : repo.getUpdatedAt().toInstant(),
            tags,
            new ArrayList<>(repo.listTopics())
        );
    }

    private GHRepository invokeRepo(String fullName) throws IOException {
        return github.getRepoRaw(fullName);
    }
}
