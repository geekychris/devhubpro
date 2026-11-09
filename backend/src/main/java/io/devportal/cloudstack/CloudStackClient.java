package io.devportal.cloudstack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.devportal.cloudstack.CloudStackDtos.Activity;
import io.devportal.cloudstack.CloudStackDtos.Jenkins;
import io.devportal.cloudstack.CloudStackDtos.PullRequest;
import io.devportal.cloudstack.CloudStackDtos.Reason;
import io.devportal.cloudstack.CloudStackDtos.Run;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Thin HTTP client for cloud-stack's API. Server-side only: the bearer token stays in the
 * backend, never goes to the browser and is never put in an exception message or log line.
 */
@Component
public class CloudStackClient {

    private static final String USER_AGENT = "devhubpro-cloudstack/1";

    private final CloudStackProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public CloudStackClient(CloudStackProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(props.timeoutMs()))
            .followRedirects(HttpClient.Redirect.NEVER)   // never replay the bearer token to another host
            .build();
    }

    /** A failed call, classified. Messages are safe to show to the user. */
    public static final class CloudStackException extends Exception {
        private final Reason reason;

        public CloudStackException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    /** {@code GET /v1/repos/activity?repo=owner/name}. */
    public Activity activity(String repo) throws CloudStackException {
        String path = "/v1/repos/activity?repo=" + URLEncoder.encode(repo, StandardCharsets.UTF_8);
        HttpResponse<String> res = send(path, true);
        int sc = res.statusCode();
        if (sc == 401 || sc == 403) {
            throw new CloudStackException(Reason.UNAUTHORIZED, "cloud-stack refused the request (HTTP " + sc + ")"
                + (props.hasToken() ? " — check the token in CLOUDSTACK_TOKEN" : " — CLOUDSTACK_TOKEN is not set"));
        }
        if (sc == 404) {
            throw new CloudStackException(Reason.UNREACHABLE,
                "cloud-stack has no /v1/repos/activity endpoint (HTTP 404) — it may need an upgrade");
        }
        if (sc < 200 || sc >= 300) {
            throw new CloudStackException(Reason.UNREACHABLE, "cloud-stack answered HTTP " + sc);
        }
        try {
            return parse(mapper.readTree(res.body()), repo);
        } catch (IOException | RuntimeException e) {
            throw new CloudStackException(Reason.BAD_RESPONSE, "cloud-stack answered something that is not activity JSON");
        }
    }

    /** {@code GET /healthz} (unauthenticated). Returns latency in ms. */
    public int ping() throws CloudStackException {
        long start = System.nanoTime();
        HttpResponse<String> res = send("/healthz", false);
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new CloudStackException(Reason.UNREACHABLE, "cloud-stack /healthz answered HTTP " + res.statusCode());
        }
        return (int) Duration.ofNanos(System.nanoTime() - start).toMillis();
    }

    private HttpResponse<String> send(String path, boolean auth) throws CloudStackException {
        if (!props.configured()) {
            throw new CloudStackException(Reason.NOT_CONFIGURED, "CLOUDSTACK_API_URL is not set");
        }
        HttpRequest.Builder b;
        try {
            b = HttpRequest.newBuilder(URI.create(props.apiUrl() + path));
        } catch (IllegalArgumentException e) {
            throw new CloudStackException(Reason.NOT_CONFIGURED, "CLOUDSTACK_API_URL is not a valid URL");
        }
        b.timeout(Duration.ofMillis(props.timeoutMs()))
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .GET();
        if (auth && props.hasToken()) b.header("Authorization", "Bearer " + props.token());
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new CloudStackException(Reason.UNREACHABLE, "cloud-stack did not answer within " + props.timeoutMs() + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudStackException(Reason.UNREACHABLE, "interrupted");
        } catch (IOException e) {
            throw new CloudStackException(Reason.UNREACHABLE, "cannot reach cloud-stack at " + props.apiUrl()
                + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    // ---- lenient parsing: unknown fields ignored, wrong shapes degrade to null/empty ----

    static Activity parse(JsonNode root, String repo) {
        if (root == null || !root.isObject()) throw new IllegalArgumentException("not an object");
        List<PullRequest> prs = new ArrayList<>();
        for (JsonNode n : array(root, "openPRs")) {
            if (!n.isObject()) continue;
            prs.add(new PullRequest(text(n, "url"), integer(n, "number"), text(n, "title"), text(n, "needs"),
                text(n, "why"), text(n, "checks"), text(n, "runId")));
        }
        Jenkins jenkins = null;
        JsonNode j = root.get("jenkins");
        if (j != null && j.isObject()) {
            jenkins = new Jenkins(text(j, "status"), text(j, "jobName"), integer(j, "buildNumber"), text(j, "sha"),
                text(j, "link"), text(j, "at"));
        }
        List<Run> runs = new ArrayList<>();
        for (JsonNode n : array(root, "runs")) {
            if (!n.isObject()) continue;
            runs.add(new Run(text(n, "id"), text(n, "state"), text(n, "workflow"), text(n, "title"), text(n, "url"),
                text(n, "createdAt")));
        }
        String r = text(root, "repo");
        return new Activity(r == null ? repo : r, prs, jenkins, runs, text(root, "newTaskUrl"));
    }

    private static Iterable<JsonNode> array(JsonNode n, String field) {
        JsonNode a = n.get(field);
        return a != null && a.isArray() ? a : List.of();
    }

    /** Scalars as text; arrays of scalars joined with ", "; objects and null as null. */
    static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || v.isMissingNode() || v.isObject()) return null;
        if (v.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode x : v) if (x.isValueNode() && !x.isNull()) parts.add(x.asText());
            return parts.isEmpty() ? null : String.join(", ", parts);
        }
        String s = v.asText();
        return s.isBlank() ? null : s;
    }

    static Integer integer(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        if (v.canConvertToInt() && v.isNumber()) return v.intValue();
        if (v.isTextual()) {
            try {
                return Integer.parseInt(v.asText().trim().replaceFirst("^#", ""));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
