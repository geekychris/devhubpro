package io.devportal.registry;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * End-to-end over the real schema (Flyway) and REST layer. Needs a disposable Postgres:
 * <pre>
 *   docker run -d --name devhub-test-pg -e POSTGRES_DB=devportal -e POSTGRES_USER=devportal \
 *     -e POSTGRES_PASSWORD=devportal -p 127.0.0.1:15499:5432 postgres:16-alpine
 *   DEVPORTAL_TEST_DB_URL=jdbc:postgresql://127.0.0.1:15499/devportal ./gradlew test
 * </pre>
 * Skipped when DEVPORTAL_TEST_DB_URL is unset.
 */
@SpringBootTest(properties = {
    "devportal.registry.token=test-token",
    "devportal.registry.health.enabled=false",
    "devportal.registry.health.interval-ms=600000",
    "devportal.registry.health.initial-delay-ms=600000",
    "devportal.registry.health.timeout-ms=2000",
    "devportal.registry.health.concurrency=4",
    "devportal.telegram.enabled=false",
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DEVPORTAL_TEST_DB_URL", matches = ".+")
class DeploymentRegistryIT {

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv("DEVPORTAL_TEST_DB_URL"));
        r.add("spring.datasource.username", () -> envOr("DEVPORTAL_TEST_DB_USER", "devportal"));
        r.add("spring.datasource.password", () -> envOr("DEVPORTAL_TEST_DB_PASSWORD", "devportal"));
        // Keep the context away from the developer's real ~/.devportal and port 2222.
        String tmp = TMP.toString();
        r.add("devportal.workspace.dir", () -> tmp + "/workspace");
        r.add("devportal.state.repo", () -> tmp + "/state");
        r.add("devportal.backup.dir", () -> tmp + "/backups");
        r.add("devportal.cli.ssh.port", () -> "0");
        r.add("devportal.cli.ssh.host-key", () -> tmp + "/secrets/ssh-host-key");
        r.add("devportal.cli.ssh.authorized-keys", () -> tmp + "/secrets/authorized_keys");
        r.add("devportal.cli.ssh.password-file", () -> tmp + "/secrets/ssh-password");
        r.add("devportal.telegram.bot-token-file", () -> tmp + "/secrets/telegram-bot-token");
        r.add("devportal.telegram.allowlist-file", () -> tmp + "/secrets/telegram-allowlist");
    }

    private static final java.nio.file.Path TMP;
    static {
        try {
            TMP = java.nio.file.Files.createTempDirectory("devhub-registry-it");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String envOr(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private ResultActions report(String body, String token) throws Exception {
        var req = post("/api/deployments/report").contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) req = req.header(RegistryConfig.TOKEN_HEADER, token);
        return mvc.perform(req);
    }

    private static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void writesNeedTheTokenReadsDoNot() throws Exception {
        String body = "{\"source\":\"t\",\"deployments\":[]}";
        report(body, null).andExpect(status().isUnauthorized());
        report(body, "wrong").andExpect(status().isUnauthorized());
        report(body, "test-token").andExpect(status().isOk());
        mvc.perform(get("/api/deployments")).andExpect(status().isOk());
        mvc.perform(get("/api/deployments/targets")).andExpect(status().isOk());
    }

    @Test
    void reportIsIdempotentAutoCreatesAssetsAndReconciles() throws Exception {
        String u = uniq();
        String source = "it-saaspack-" + u;
        String target = "wopr-" + u;
        String batch = """
            {"source":"%s","reconcile":true,"reconcileTargets":["%s"],"deployments":[
              {"asset":{"name":"Kafka %s","repoUrl":"https://github.com/apache/kafka","tags":["saaspack"]},
               "target":"%s","targetKind":"k8s-cluster","namespace":"saaspack","name":"kafka",
               "version":"3.7.0","status":"healthy","labels":{"component":"kafka"},
               "urls":[{"label":"UI","url":"ssh://not-polled","primary":true}]},
              {"asset":{"name":"OpenSearch %s"},"target":"%s","targetKind":"k8s-cluster","namespace":"saaspack"}
            ]}""".formatted(source, target, u, target, u, target);

        JsonNode first = json.readTree(report(batch, "test-token").andExpect(status().isOk())
            .andExpect(jsonPath("$.created", is(2)))
            .andExpect(jsonPath("$.updated", is(0)))
            .andExpect(jsonPath("$.assetsCreated", hasSize(2)))
            .andReturn().getResponse().getContentAsString());
        String kafkaId = "kafka-" + u;
        JsonNode kafka = first.get("deployments").get(0);
        org.junit.jupiter.api.Assertions.assertEquals(kafkaId, kafka.get("assetId").asText());
        org.junit.jupiter.api.Assertions.assertEquals("healthy", kafka.get("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals(
            kafkaId + "|" + target + "|saaspack|kafka", kafka.get("key").asText());

        // the auto-created asset exists, tagged, and lists its deployment
        mvc.perform(get("/api/assets/" + kafkaId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.tags", hasItem("registry")))
            .andExpect(jsonPath("$.repoUrl", is("https://github.com/apache/kafka")));
        mvc.perform(get("/api/assets/" + kafkaId + "/deployments"))
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].version", is("3.7.0")));

        // same batch again: pure update, nothing new
        report(batch, "test-token").andExpect(status().isOk())
            .andExpect(jsonPath("$.created", is(0)))
            .andExpect(jsonPath("$.updated", is(2)))
            .andExpect(jsonPath("$.assetsCreated", hasSize(0)));

        // a part disappears: reconcile retires it; a deployment of the same source on
        // another target is untouched because the reconcile is scoped to `target`
        String other = """
            {"source":"%s","deployments":[{"assetId":"%s","target":"mp2-%s","status":"down"}]}
            """.formatted(source, kafkaId, u);
        report(other, "test-token").andExpect(jsonPath("$.created", is(1)));
        String onlyKafka = """
            {"source":"%s","reconcile":true,"reconcileTargets":["%s"],"deployments":[
              {"assetId":"%s","target":"%s","targetKind":"k8s-cluster","namespace":"saaspack","name":"kafka"}]}
            """.formatted(source, target, kafkaId, target);
        report(onlyKafka, "test-token").andExpect(jsonPath("$.retired", is(1)));

        mvc.perform(get("/api/deployments").param("source", source))
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[*].assetId", not(hasItem("opensearch-" + u))));
        mvc.perform(get("/api/deployments").param("source", source).param("includeRetired", "true"))
            .andExpect(jsonPath("$", hasSize(3)));
        mvc.perform(get("/api/deployments").param("status", "retired").param("source", source))
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].assetId", is("opensearch-" + u)));

        // targets roll-up
        mvc.perform(get("/api/deployments/targets"))
            .andExpect(jsonPath("$[?(@.target == '" + target + "')].total", hasItem(1)))
            .andExpect(jsonPath("$[?(@.target == 'mp2-" + u + "')].down", hasItem(1)));

        // empty reconcile retires the rest of the source
        report("{\"source\":\"%s\",\"reconcile\":true,\"deployments\":[]}".formatted(source), "test-token")
            .andExpect(jsonPath("$.retired", is(2)));
        mvc.perform(get("/api/deployments").param("source", source)).andExpect(jsonPath("$", hasSize(0)));

        // re-reporting a retired deployment revives it
        report(onlyKafka, "test-token").andExpect(jsonPath("$.updated", is(1)))
            .andExpect(jsonPath("$.deployments[0].retiredAt").doesNotExist());
    }

    @Test
    void badReportsAre400() throws Exception {
        report("{\"source\":\"t\",\"deployments\":[{\"assetId\":\"x\",\"target\":\"wopr\"}]}", "test-token")
            .andExpect(status().isBadRequest());
        report("{\"source\":\"t\",\"deployments\":[{\"assetId\":\"good-id\"}]}", "test-token")
            .andExpect(status().isBadRequest());
        report("{\"reconcile\":true,\"deployments\":[]}", "test-token")
            .andExpect(status().isBadRequest());
        String u = uniq();
        report("""
            {"source":"t","deployments":[
              {"assetId":"dup-%s","target":"a"},{"assetId":"dup-%s","target":"a"}]}
            """.formatted(u, u), "test-token").andExpect(status().isBadRequest());
    }

    @Test
    void checkProbesTheHealthUrlAndRetireDeleteWork() throws Exception {
        HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/ok", ex -> { ex.sendResponseHeaders(200, -1); ex.close(); });
        fake.createContext("/broken", ex -> { ex.sendResponseHeaders(503, -1); ex.close(); });
        fake.start();
        try {
            int port = fake.getAddress().getPort();
            String u = uniq();
            String body = """
                {"source":"it-%s","deployments":[
                  {"assetId":"svc-%s","asset":{"name":"svc"},"target":"spark","name":"ok",
                   "healthUrl":"http://127.0.0.1:%d/ok"},
                  {"assetId":"svc-%s","target":"spark","name":"broken",
                   "urls":[{"label":"UI","url":"http://127.0.0.1:%d/broken","primary":true}]},
                  {"assetId":"svc-%s","target":"spark","name":"gone","healthUrl":"http://127.0.0.1:1/nothing"}
                ]}""".formatted(u, u, port, u, port, u);
            JsonNode res = json.readTree(report(body, "test-token").andExpect(status().isOk())
                .andExpect(jsonPath("$.deployments[0].status", is("unknown")))
                .andReturn().getResponse().getContentAsString());
            long ok = res.get("deployments").get(0).get("id").asLong();
            long broken = res.get("deployments").get(1).get("id").asLong();
            long gone = res.get("deployments").get(2).get("id").asLong();

            mvc.perform(post("/api/deployments/" + ok + "/check").header(RegistryConfig.TOKEN_HEADER, "test-token"))
                .andExpect(jsonPath("$.status", is("healthy")))
                .andExpect(jsonPath("$.lastCheckedAt").exists());
            mvc.perform(post("/api/deployments/" + broken + "/check").header(RegistryConfig.TOKEN_HEADER, "test-token"))
                .andExpect(jsonPath("$.status", is("down")))
                .andExpect(jsonPath("$.lastError", is("HTTP 503")));
            mvc.perform(post("/api/deployments/" + gone + "/check").header(RegistryConfig.TOKEN_HEADER, "test-token"))
                .andExpect(jsonPath("$.status", is("down")))
                .andExpect(jsonPath("$.lastError").exists());

            mvc.perform(post("/api/deployments/" + ok + "/retire").header(RegistryConfig.TOKEN_HEADER, "test-token"))
                .andExpect(jsonPath("$.status", is("retired")));
            mvc.perform(delete("/api/deployments/" + ok)).andExpect(status().isUnauthorized());
            mvc.perform(delete("/api/deployments/" + ok).header(RegistryConfig.TOKEN_HEADER, "test-token"))
                .andExpect(status().isNoContent());
            mvc.perform(get("/api/deployments/" + ok)).andExpect(status().isNotFound());
        } finally {
            fake.stop(0);
        }
    }
}
