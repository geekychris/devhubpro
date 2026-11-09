package io.devportal.cloudstack;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The cloud-stack endpoints over the full Spring context (config binding, REST layer, real
 * asset table) with a fake cloud-stack. Needs a disposable Postgres, like DeploymentRegistryIT:
 * {@code DEVPORTAL_TEST_DB_URL=jdbc:postgresql://127.0.0.1:15499/devportal ./gradlew test}.
 */
@SpringBootTest(properties = {
    "devportal.registry.health.enabled=false",
    "devportal.telegram.enabled=false",
    "cloudstack.token=it-token-not-for-output",
    "cloudstack.ui-url=http://ui.example:30880",
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DEVPORTAL_TEST_DB_URL", matches = ".+")
class CloudStackIT {

    private static final HttpServer FAKE;
    private static final java.nio.file.Path TMP;
    static {
        try {
            TMP = java.nio.file.Files.createTempDirectory("devhub-cloudstack-it");
            FAKE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FAKE.createContext("/v1/repos/activity", ex -> {
                boolean ok = "Bearer it-token-not-for-output".equals(ex.getRequestHeaders().getFirst("Authorization"));
                String b = ok
                    ? "{\"repo\":\"geekychris/it-repo\",\"openPRs\":[{\"url\":\"https://github.com/geekychris/it-repo/pull/1\","
                      + "\"number\":1,\"title\":\"T\",\"needs\":\"review\",\"checks\":\"pending\"}],\"jenkins\":null,\"runs\":[]}"
                    : "{\"error\":\"unauthorized\"}";
                byte[] bytes = b.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(ok ? 200 : 401, bytes.length);
                ex.getResponseBody().write(bytes);
                ex.close();
            });
            FAKE.createContext("/healthz", ex -> {
                ex.sendResponseHeaders(200, -1);
                ex.close();
            });
            FAKE.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stopFake() {
        FAKE.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv("DEVPORTAL_TEST_DB_URL"));
        r.add("spring.datasource.username", () -> envOr("DEVPORTAL_TEST_DB_USER", "devportal"));
        r.add("spring.datasource.password", () -> envOr("DEVPORTAL_TEST_DB_PASSWORD", "devportal"));
        r.add("cloudstack.api-url", () -> "http://127.0.0.1:" + FAKE.getAddress().getPort());
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

    private static String envOr(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }

    @Autowired MockMvc mvc;

    private String createAsset(String repoUrl) throws Exception {
        String id = "cs-it-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/assets").contentType(MediaType.APPLICATION_JSON).content(
            "{\"id\":\"%s\",\"name\":\"%s\",\"type\":\"service\",\"repoUrl\":\"%s\"}".formatted(id, id, repoUrl)))
            .andExpect(status().is2xxSuccessful());
        return id;
    }

    @Test
    void assetActivityComesFromCloudStack() throws Exception {
        String id = createAsset("https://github.com/geekychris/it-repo.git");
        mvc.perform(get("/api/assets/" + id + "/cloudstack"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available", is(true)))
            .andExpect(jsonPath("$.repo", is("geekychris/it-repo")))
            .andExpect(jsonPath("$.openPRs", hasSize(1)))
            .andExpect(jsonPath("$.openPRs[0].checks", is("pending")))
            .andExpect(jsonPath("$.newTaskUrl",
                is("http://ui.example:30880/#/new?repo=https%3A%2F%2Fgithub.com%2Fgeekychris%2Fit-repo")))
            .andExpect(content().string(not(containsString("it-token-not-for-output"))));
    }

    @Test
    void nonGithubAssetIsUnavailable() throws Exception {
        String id = createAsset("https://gitlab.com/me/thing.git");
        mvc.perform(get("/api/assets/" + id + "/cloudstack"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available", is(false)))
            .andExpect(jsonPath("$.reasonCode", is("no-github-repo")));
    }

    @Test
    void unknownAssetIs404() throws Exception {
        mvc.perform(get("/api/assets/no-such-asset-xyz/cloudstack")).andExpect(status().isNotFound());
    }

    @Test
    void statusNeverIncludesTheToken() throws Exception {
        mvc.perform(get("/api/cloudstack/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configured", is(true)))
            .andExpect(jsonPath("$.tokenConfigured", is(true)))
            .andExpect(jsonPath("$.reachable", is(true)))
            .andExpect(content().string(not(containsString("it-token-not-for-output"))));
    }
}
