package io.devportal.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ManifestParserTest {

    private ManifestParser parser;

    @BeforeEach
    void setUp() throws Exception {
        parser = new ManifestParser();
        parser.loadSchema();
    }

    @Test
    void parsesValidManifest() throws IOException {
        ManifestParseResult result = parser.parse(read("manifests/valid.yaml"));

        assertThat(result.valid()).as("errors=%s", result.errors()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.manifest()).isNotNull();
        assertThat(result.manifest().metadata().id()).isEqualTo("hitorro-core");
        assertThat(result.manifest().spec().type()).isEqualTo("library");
        assertThat(result.manifest().spec().dependencies()).hasSize(1);
        assertThat(result.manifest().spec().dependencies().get(0).id()).isEqualTo("hitorro-util");
        assertThat(result.manifest().spec().runtime().ports()).hasSize(1);
        assertThat(result.manifest().spec().runtime().ports().get(0).name()).isEqualTo("http");
    }

    @Test
    void rejectsManifestWithoutId() throws IOException {
        ManifestParseResult result = parser.parse(read("manifests/invalid-missing-id.yaml"));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors())
            .anyMatch(e -> e.message().toLowerCase().contains("id"));
    }

    @Test
    void rejectsBadPortSlotName() throws IOException {
        ManifestParseResult result = parser.parse(read("manifests/invalid-bad-port-name.yaml"));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors())
            .anyMatch(e -> e.path().contains("ports") && e.message().toLowerCase().contains("pattern"));
    }

    @Test
    void handlesGarbageYaml() {
        ManifestParseResult result = parser.parse(": : not yaml");

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }

    @Test
    void handlesEmptyManifest() {
        ManifestParseResult result = parser.parse("");

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }

    private String read(String resourcePath) throws IOException {
        return new String(new ClassPathResource(resourcePath).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
