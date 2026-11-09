package io.devportal.cloudstack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.devportal.cloudstack.CloudStackDtos.AssetActivity;
import io.devportal.cloudstack.CloudStackDtos.Status;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** CloudStackService + CloudStackClient against a fake cloud-stack (JDK HttpServer). */
class CloudStackServiceTest {

    private static final String TOKEN = "s3cret-test-token";
    private static final String UI = "http://spark.example:30880";

    private HttpServer server;
    private String base;
    private final AtomicInteger activityCalls = new AtomicInteger();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final AtomicLong delayMs = new AtomicLong();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/repos/activity", ex -> {
            activityCalls.incrementAndGet();
            authHeaders.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            queries.add(ex.getRequestURI().getRawQuery());
            sleep(delayMs.get());
            byte[] b = body.get().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status.get(), b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/healthz", ex -> {
            byte[] b = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private CloudStackService service(String apiUrl, String token, String ui, long timeoutMs) {
        CloudStackProperties p = new CloudStackProperties(apiUrl, token, ui, timeoutMs, 30_000L);
        return new CloudStackService(p, new CloudStackClient(p, new ObjectMapper()), null, now::get);
    }

    private CloudStackService service() {
        return service(base + "/", TOKEN, UI + "/", 3_000);
    }

    private static final String FULL = """
        {"repo":"geekychris/devhubpro",
         "openPRs":[{"url":"https://github.com/geekychris/devhubpro/pull/3","number":3,"title":"Add thing",
                     "needs":"review","why":"checks passed","checks":"passing","runId":"r-42","extra":{"x":1}},
                    {"url":"javascript:alert(1)","number":"#4","title":"Odd","needs":["rebase","checks"],
                     "checks":"FAILING"}],
         "jenkins":{"status":"succeeded","jobName":"devhubpro/main","buildNumber":17,"sha":"abc123",
                    "link":"http://jenkins.example/job/devhubpro/17","at":"2026-11-08T10:00:00Z","new":true},
         "runs":[{"id":"r-42","state":"running","workflow":"sdlc@3","title":"Add thing",
                  "url":"http://spark.example:30880/#/runs/r-42","createdAt":"2026-11-08T09:00:00Z"}],
         "newTaskUrl":"http://cs.example/#/new?repo=https%3A%2F%2Fgithub.com%2Fgeekychris%2Fdevhubpro",
         "somethingNew":[1,2,3]}
        """;

    @Test
    void returnsActivityLenientlyAndSendsBearerToken() {
        body.set(FULL);
        AssetActivity a = service().forRepoUrl("devhubpro", "https://github.com/geekychris/devhubpro.git");

        assertTrue(a.available());
        assertNull(a.reasonCode());
        assertEquals("geekychris/devhubpro", a.repo());
        assertEquals("https://github.com/geekychris/devhubpro", a.repoUrl());
        assertEquals(List.of("Bearer " + TOKEN), authHeaders);
        assertEquals("repo=geekychris%2Fdevhubpro", queries.get(0));

        assertEquals(2, a.openPRs().size());
        var pr = a.openPRs().get(0);
        assertEquals(3, pr.number());
        assertEquals("review", pr.needs());
        assertEquals("passing", pr.checks());
        assertEquals("r-42", pr.runId());
        var odd = a.openPRs().get(1);
        assertNull(odd.url(), "non-http links are dropped");
        assertEquals(4, odd.number());
        assertEquals("rebase, checks", odd.needs());
        assertEquals("failing", odd.checks());

        assertEquals("succeeded", a.jenkins().status());
        assertEquals(17, a.jenkins().buildNumber());
        assertEquals("http://jenkins.example/job/devhubpro/17", a.jenkins().link());
        assertEquals(1, a.runs().size());
        assertEquals("running", a.runs().get(0).state());
        // cloud-stack's own link wins
        assertEquals("http://cs.example/#/new?repo=https%3A%2F%2Fgithub.com%2Fgeekychris%2Fdevhubpro", a.newTaskUrl());
    }

    @Test
    void buildsNewTaskUrlFromUiUrlWhenCloudStackSendsNone() {
        body.set("{\"repo\":\"geekychris/devhubpro\",\"openPRs\":[],\"jenkins\":null,\"runs\":[]}");
        AssetActivity a = service().forRepoUrl("x", "git@github.com:geekychris/devhubpro.git");
        assertTrue(a.available());
        assertNull(a.jenkins());
        assertTrue(a.openPRs().isEmpty());
        assertEquals(UI + "/#/new?repo=https%3A%2F%2Fgithub.com%2Fgeekychris%2Fdevhubpro", a.newTaskUrl());
    }

    @Test
    void missingFieldsDefaultToEmpty() {
        body.set("{}");
        AssetActivity a = service().forRepoUrl("x", "https://github.com/geekychris/devhubpro");
        assertTrue(a.available());
        assertEquals("geekychris/devhubpro", a.repo());
        assertTrue(a.openPRs().isEmpty());
        assertTrue(a.runs().isEmpty());
        assertNull(a.jenkins());
    }

    @Test
    void cachesPerRepoForTtl() {
        body.set(FULL);
        CloudStackService s = service();
        s.forRepoUrl("a", "https://github.com/geekychris/devhubpro");
        s.forRepoUrl("b", "https://github.com/GeekyChris/DevHubPro");
        assertEquals(1, activityCalls.get());
        s.forRepoUrl("c", "https://github.com/geekychris/other");
        assertEquals(2, activityCalls.get());
        now.addAndGet(31_000);
        s.forRepoUrl("a", "https://github.com/geekychris/devhubpro");
        assertEquals(3, activityCalls.get());
    }

    @Test
    void notConfiguredDoesNotCallOut() {
        AssetActivity a = service("", TOKEN, UI, 3_000).forRepoUrl("x", "https://github.com/geekychris/devhubpro");
        assertFalse(a.available());
        assertEquals("not-configured", a.reasonCode());
        assertEquals(0, activityCalls.get());
        // the New task link still works from the UI URL alone
        assertEquals(UI + "/#/new?repo=https%3A%2F%2Fgithub.com%2Fgeekychris%2Fdevhubpro", a.newTaskUrl());
    }

    @Test
    void nonGithubOrMissingRepo() {
        CloudStackService s = service();
        AssetActivity none = s.forRepoUrl("x", null);
        assertFalse(none.available());
        assertEquals("no-github-repo", none.reasonCode());
        assertNull(none.newTaskUrl());
        AssetActivity gitlab = s.forRepoUrl("x", "https://gitlab.com/me/thing.git");
        assertEquals("no-github-repo", gitlab.reasonCode());
        assertTrue(gitlab.reason().contains("not on GitHub"));
        assertEquals(0, activityCalls.get());
    }

    @Test
    void notFoundIsUnavailableAndFailuresAreCachedBriefly() {
        status.set(404);
        body.set("{\"error\":\"not found\"}");
        CloudStackService s = service();
        AssetActivity a = s.forRepoUrl("x", "https://github.com/geekychris/devhubpro");
        assertFalse(a.available());
        assertEquals("unreachable", a.reasonCode());
        assertNotNull(a.newTaskUrl());
        s.forRepoUrl("x", "https://github.com/geekychris/devhubpro");
        assertEquals(1, activityCalls.get());
        now.addAndGet(CloudStackService.FAILURE_TTL_MS + 1);
        status.set(200);
        body.set(FULL);
        assertTrue(s.forRepoUrl("x", "https://github.com/geekychris/devhubpro").available());
        assertEquals(2, activityCalls.get());
    }

    @Test
    void unauthorizedNeverEchoesTheToken() {
        status.set(401);
        AssetActivity a = service().forRepoUrl("x", "https://github.com/geekychris/devhubpro");
        assertEquals("unauthorized", a.reasonCode());
        assertFalse(a.reason().contains(TOKEN));
        assertFalse(a.toString().contains(TOKEN));
    }

    @Test
    void badJsonIsBadResponse() {
        body.set("<html>oops</html>");
        AssetActivity a = service().forRepoUrl("x", "https://github.com/geekychris/devhubpro");
        assertEquals("bad-response", a.reasonCode());
    }

    @Test
    void slowCloudStackTimesOut() {
        delayMs.set(1_500);
        body.set(FULL);
        long start = System.nanoTime();
        AssetActivity a = service(base, TOKEN, UI, 300).forRepoUrl("x", "https://github.com/geekychris/devhubpro");
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertEquals("unreachable", a.reasonCode());
        assertTrue(ms < 1_400, "took " + ms + " ms");
    }

    @Test
    void connectionRefusedIsUnreachable() throws IOException {
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        AssetActivity a = service("http://127.0.0.1:" + port, TOKEN, UI, 1_000)
            .forRepoUrl("x", "https://github.com/geekychris/devhubpro");
        assertFalse(a.available());
        assertEquals("unreachable", a.reasonCode());
    }

    @Test
    void statusReportsConfiguredAndReachable() {
        Status s = service().status();
        assertTrue(s.configured());
        assertTrue(s.tokenConfigured());
        assertEquals(Boolean.TRUE, s.reachable());
        assertEquals(base, s.apiUrl());
        assertEquals(UI, s.uiUrl());

        Status off = service(null, null, null, 3_000).status();
        assertFalse(off.configured());
        assertFalse(off.tokenConfigured());
        assertNull(off.reachable());
    }

    @Test
    void propertiesToStringMasksToken() {
        CloudStackProperties p = new CloudStackProperties(base, TOKEN, UI, null, null);
        assertFalse(p.toString().contains(TOKEN));
        assertEquals(3_000L, p.timeoutMs());
        assertEquals(30_000L, p.cacheTtlMs());
    }

    @Test
    void githubRepoParsing() {
        assertEquals("o/r", CloudStackService.githubRepo("https://github.com/o/r"));
        assertEquals("o/r", CloudStackService.githubRepo("https://www.github.com/o/r.git"));
        assertEquals("o/r", CloudStackService.githubRepo("git@github.com:o/r.git"));
        assertNull(CloudStackService.githubRepo("https://bitbucket.org/o/r"));
        assertNull(CloudStackService.githubRepo(""));
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
