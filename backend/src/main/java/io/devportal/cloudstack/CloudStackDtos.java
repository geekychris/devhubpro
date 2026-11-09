package io.devportal.cloudstack;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** What {@code GET /api/assets/{id}/cloudstack} and {@code GET /api/cloudstack/status} return. */
public final class CloudStackDtos {

    private CloudStackDtos() {}

    /**
     * Why cloud-stack activity is missing: {@code not-configured} (CLOUDSTACK_API_URL unset),
     * {@code no-github-repo} (the asset has no GitHub repo URL), {@code unreachable} (connection
     * error, timeout, 404 or 5xx), {@code unauthorized} (401/403 — token missing or wrong),
     * {@code bad-response} (cloud-stack answered something that is not the expected JSON).
     */
    public enum Reason {
        NOT_CONFIGURED("not-configured"),
        NO_GITHUB_REPO("no-github-repo"),
        UNREACHABLE("unreachable"),
        UNAUTHORIZED("unauthorized"),
        BAD_RESPONSE("bad-response");

        public final String code;

        Reason(String code) {
            this.code = code;
        }
    }

    public record PullRequest(String url, Integer number, String title, String needs, String why,
                              String checks, String runId) {}

    public record Jenkins(String status, String jobName, Integer buildNumber, String sha, String link, String at) {}

    public record Run(String id, String state, String workflow, String title, String url, String createdAt) {}

    /** Activity as cloud-stack reported it (unknown fields dropped, missing ones defaulted). */
    public record Activity(String repo, List<PullRequest> openPRs, Jenkins jenkins, List<Run> runs, String newTaskUrl) {}

    /**
     * One asset's cloud-stack view. When {@code available} is false, {@code reasonCode}/{@code reason}
     * say why; {@code newTaskUrl} is still set whenever the repo is on GitHub and a UI URL is known.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AssetActivity(
        String assetId,
        boolean available,
        String reasonCode,
        String reason,
        String repo,
        String repoUrl,
        List<PullRequest> openPRs,
        Jenkins jenkins,
        List<Run> runs,
        String newTaskUrl,
        String uiUrl,
        String fetchedAt
    ) {}

    /** {@code GET /api/cloudstack/status}. The token itself is never included. */
    public record Status(
        boolean configured,
        boolean tokenConfigured,
        String apiUrl,
        String uiUrl,
        Boolean reachable,
        String error,
        Integer latencyMs,
        String checkedAt
    ) {}
}
