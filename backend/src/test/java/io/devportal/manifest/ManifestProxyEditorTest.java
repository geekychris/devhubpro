package io.devportal.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ManifestProxyEditorTest {

    @Test
    void addsRuntimeAndProxyWhenAbsent() {
        String src = String.join("\n",
            "apiVersion: devportal.io/v1",
            "kind: Asset",
            "metadata:",
            "  id: foo",
            "  name: Foo",
            "spec:",
            "  type: service",
            "  docker:",
            "    enabled: true",
            "");
        String out = ManifestProxyEditor.setProxy(src, "/foo", "http", true, null);
        assertThat(out).contains("  runtime:");
        assertThat(out).contains("    proxy:");
        assertThat(out).contains("      path: /foo");
        assertThat(out).contains("      portSlot: http");
        // Pre-existing sections preserved.
        assertThat(out).contains("kind: Asset");
        assertThat(out).contains("    enabled: true");
    }

    @Test
    void addsProxyUnderExistingRuntime() {
        String src = String.join("\n",
            "apiVersion: devportal.io/v1",
            "kind: Asset",
            "metadata:",
            "  id: foo",
            "  name: Foo",
            "spec:",
            "  type: service",
            "  runtime:",
            "    ports:",
            "      - name: http",
            "");
        String out = ManifestProxyEditor.setProxy(src, "/foo", "http", true, null);
        assertThat(out).contains("    ports:");
        assertThat(out).contains("    proxy:");
        // ports list is still there.
        assertThat(out).contains("      - name: http");
    }

    @Test
    void replacesExistingProxyInPlace() {
        String src = String.join("\n",
            "spec:",
            "  type: service",
            "  runtime:",
            "    proxy:",
            "      path: /old",
            "      portSlot: http",
            "    ports:",
            "      - name: http",
            "");
        String out = ManifestProxyEditor.setProxy(src, "/new", "http", false, "portal.local");
        assertThat(out).contains("      path: /new");
        assertThat(out).doesNotContain("      path: /old");
        assertThat(out).contains("      stripPrefix: false");
        assertThat(out).contains("      host: portal.local");
        // Sibling ports list still in place after the replaced proxy block.
        assertThat(out).contains("    ports:");
    }

    @Test
    void removesProxyAndLeavesRuntime() {
        String src = String.join("\n",
            "spec:",
            "  type: service",
            "  runtime:",
            "    proxy:",
            "      path: /foo",
            "      portSlot: http",
            "    ports:",
            "      - name: http",
            "");
        String out = ManifestProxyEditor.removeProxy(src);
        assertThat(out).doesNotContain("proxy");
        assertThat(out).contains("    ports:");
        assertThat(out).contains("  runtime:");
    }

    @Test
    void removeIsNoOpWhenAbsent() {
        String src = String.join("\n",
            "spec:",
            "  type: service",
            "  runtime:",
            "    ports:",
            "      - name: http",
            "");
        assertThat(ManifestProxyEditor.removeProxy(src)).isEqualTo(src);
    }

    @Test
    void hostOnlyEntryOmitsPath() {
        String src = String.join("\n",
            "spec:",
            "  type: service",
            "");
        String out = ManifestProxyEditor.setProxy(src, null, "http", true, "esp.example.com");
        assertThat(out).contains("    proxy:");
        assertThat(out).contains("      portSlot: http");
        assertThat(out).contains("      host: esp.example.com");
        assertThat(out).doesNotContain("      path:");
    }

    @Test
    void rejectsWhenNeitherPathNorHost() {
        String src = "spec:\n  type: service\n";
        org.assertj.core.api.Assertions
            .assertThatThrownBy(() -> ManifestProxyEditor.setProxy(src, null, "http", true, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesCommentsAndBlankLines() {
        String src = String.join("\n",
            "# top",
            "apiVersion: devportal.io/v1",
            "metadata:",
            "  id: foo",
            "  name: Foo",
            "",
            "spec:",
            "  # docker section",
            "  docker:",
            "    enabled: true",
            "");
        String out = ManifestProxyEditor.setProxy(src, "/foo", "http", true, null);
        assertThat(out).contains("# top");
        assertThat(out).contains("  # docker section");
        assertThat(out).contains("    enabled: true");
        assertThat(out).contains("    proxy:");
    }
}
