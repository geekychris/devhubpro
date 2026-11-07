package io.devportal.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.devportal.asset.error.BadRequestException;
import io.devportal.registry.DeploymentRepository.Upsert;
import io.devportal.registry.RegistryDtos.DeploymentReport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeploymentsTest {

    @Test
    void slugifyNamesAndRepoUrls() {
        assertEquals("lantern-market", Deployments.slugify("Lantern Market"));
        assertEquals("swift-hello-m1", Deployments.slugify("https://github.com/geekychris/swift-hello-m1.git"));
        assertEquals("swift-hello", Deployments.slugify("git@github.com:me/Swift_Hello.git"));
        assertEquals("kafka", Deployments.slugify("  Kafka!! "));
        assertEquals("abc", Deployments.slugify("42-abc"));
        assertNull(Deployments.slugify("x"));
        assertNull(Deployments.slugify(""));
        assertNull(Deployments.slugify(null));
        String longName = "a".repeat(100);
        assertEquals(64, Deployments.slugify(longName).length());
        assertTrue(Deployments.validAssetId(Deployments.slugify(longName)));
    }

    @Test
    void probeUrlPrefersHealthThenPrimaryThenFirstHttp() {
        List<DeploymentUrl> urls = List.of(
            new DeploymentUrl("ssh", "ssh://m1.local", false),
            new DeploymentUrl("api", "http://wopr:31000/api", false),
            new DeploymentUrl("UI", "http://wopr:31080", true));
        assertEquals("http://wopr:31080/health", Deployments.probeUrl("http://wopr:31080/health", "running", urls));
        assertEquals("http://wopr:31080", Deployments.probeUrl(null, "running", urls));
        assertEquals("http://wopr:31000/api",
            Deployments.probeUrl(null, "running", List.of(urls.get(0), urls.get(1))));
        // a delivered (built, not running) deployment is only probed via an explicit health URL
        assertNull(Deployments.probeUrl(null, "delivered", urls));
        assertNull(Deployments.probeUrl("ssh://x", "running", List.of(urls.get(0))));
    }

    @Test
    void classifyHttpStatuses() {
        assertEquals("healthy", Deployments.classify(200).status());
        assertEquals("healthy", Deployments.classify(302).status());
        assertEquals("healthy", Deployments.classify(401).status());
        assertEquals("down", Deployments.classify(503).status());
        assertEquals("degraded", Deployments.classify(500).status());
        assertEquals("degraded", Deployments.classify(404).status());
        assertEquals("HTTP 404", Deployments.classify(404).error());
    }

    private static DeploymentReport report(String target, String kind, String phase, String status,
                                           List<DeploymentUrl> urls, String health) {
        return new DeploymentReport("kafka", null, target, kind, " saaspack ", null, "3.7", null, null,
            phase, urls, health, status, Map.of("component", "kafka"), null, null);
    }

    @Test
    void normalizeAppliesDefaultsAndValidates() {
        Upsert u = DeploymentService.normalize(report("wopr", null, null, null, null, null), "kafka", "cs", 0);
        assertEquals("other", u.targetKind());
        assertEquals("running", u.phase());
        assertEquals("saaspack", u.namespace());
        assertEquals("", u.name());
        assertEquals("cs", u.source());
        assertNull(u.reportedStatus());

        assertThrows(BadRequestException.class,
            () -> DeploymentService.normalize(report(" ", null, null, null, null, null), "kafka", "cs", 0));
        assertThrows(BadRequestException.class,
            () -> DeploymentService.normalize(report("wopr", "mainframe", null, null, null, null), "kafka", "cs", 0));
        assertThrows(BadRequestException.class,
            () -> DeploymentService.normalize(report("wopr", null, "exploded", null, null, null), "kafka", "cs", 0));
        assertThrows(BadRequestException.class,
            () -> DeploymentService.normalize(report("wopr", null, null, "retired", null, null), "kafka", "cs", 0));

        Upsert m = DeploymentService.normalize(report("wopr", "K8S-CLUSTER", "Delivered", "Healthy",
            List.of(new DeploymentUrl(null, " http://x ", true), new DeploymentUrl("b", " ", false)), null),
            "kafka", null, 0);
        assertEquals("k8s-cluster", m.targetKind());
        assertEquals("delivered", m.phase());
        assertEquals("healthy", m.reportedStatus());
        assertEquals("manual", m.source());
        assertEquals(List.of(new DeploymentUrl("link", "http://x", true)), m.urls());
    }

    private static Deployment existing(String status, String health, Instant retiredAt) {
        return new Deployment(7, "k", "kafka", "Kafka", "wopr", "k8s-cluster", "", "", null, null, null,
            "running", List.of(), health, "cs", Map.of(), null, status, null, null, null, null,
            Instant.now(), Instant.now(), retiredAt);
    }

    @Test
    void statusDecision() {
        // nothing to probe: the reporter's word, or 'reported'
        assertEquals("reported", DeploymentService.decideStatus(null, null, null).status());
        assertEquals("down", DeploymentService.decideStatus(existing("healthy", null, null), null, "down").status());
        // something to probe: new rows wait for the poller
        assertEquals("unknown", DeploymentService.decideStatus(null, "http://h", "healthy").status());
        // existing polled row keeps its last check
        var keep = DeploymentService.decideStatus(existing("healthy", "http://h", null), "http://h", null);
        assertEquals("healthy", keep.status());
        assertFalse(keep.resetCheck());
        // probe URL changed, or revived from retired: start over
        assertEquals("unknown", DeploymentService.decideStatus(existing("healthy", "http://old", null), "http://h", null).status());
        assertEquals("unknown", DeploymentService.decideStatus(existing("retired", "http://h", Instant.now()), "http://h", null).status());
    }

    @Test
    void reconcileRetiresOnlyMissingRowsOfScopedTargets() {
        Deployment a = withIdTarget(1, "wopr");
        Deployment b = withIdTarget(2, "wopr");
        Deployment c = withIdTarget(3, "mp2");
        assertEquals(List.of(2L, 3L), DeploymentService.toRetire(List.of(a, b, c), Set.of(1L), Set.of()));
        assertEquals(List.of(2L), DeploymentService.toRetire(List.of(a, b, c), Set.of(1L), Set.of("wopr")));
        assertEquals(List.of(), DeploymentService.toRetire(List.of(a, b, c), Set.of(1L, 2L, 3L), Set.of()));
    }

    private static Deployment withIdTarget(long id, String target) {
        return new Deployment(id, "k" + id, "kafka", "Kafka", target, "k8s-cluster", "", "n" + id, null, null,
            null, "running", List.of(), null, "cs", Map.of(), null, "reported", null, null, null, null,
            Instant.now(), Instant.now(), null);
    }

    @Test
    void tokenComparison() {
        assertTrue(RegistryConfig.TokenInterceptor.matches("s3cret", "s3cret"));
        assertTrue(RegistryConfig.TokenInterceptor.matches(" s3cret ", "s3cret"));
        assertFalse(RegistryConfig.TokenInterceptor.matches("nope", "s3cret"));
        assertFalse(RegistryConfig.TokenInterceptor.matches(null, "s3cret"));
    }
}
