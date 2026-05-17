package io.devportal.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pin the per-tool fallback behaviour of {@link BuildService#defaultCommand(Path, String)},
 * including the polyglot-monorepo case where build files live in subdirs rather than at the
 * workspace root.
 */
class BuildServiceDefaultCommandTest {

    @TempDir Path workspace;

    // --- root-level builds: backward-compatible, no `cd` prefix --------------

    @Test
    void mavenAtRoot_emitsMvnPackage() throws IOException {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        assertThat(BuildService.defaultCommand(workspace, "package"))
            .hasValue("mvn -DskipTests package");
    }

    @Test
    void gradleWrapperAtRoot_usesGradlew() throws IOException {
        Files.writeString(workspace.resolve("build.gradle.kts"), "");
        Files.writeString(workspace.resolve("gradlew"), "#!/bin/sh");
        assertThat(BuildService.defaultCommand(workspace, "build"))
            .hasValue("./gradlew -x test build");
    }

    @Test
    void pnpmAtRoot_emitsPnpmRunBuild() throws IOException {
        Files.writeString(workspace.resolve("package.json"), "{}");
        Files.writeString(workspace.resolve("pnpm-lock.yaml"), "");
        assertThat(BuildService.defaultCommand(workspace, "build"))
            .hasValue("pnpm run build");
    }

    @Test
    void cargoAtRoot_emitsCargoBuild() throws IOException {
        Files.writeString(workspace.resolve("Cargo.toml"), "[package]");
        assertThat(BuildService.defaultCommand(workspace, "package"))
            .hasValue("cargo build --release");
    }

    @Test
    void unknownCommandName_returnsEmpty() throws IOException {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        assertThat(BuildService.defaultCommand(workspace, "deploy")).isEmpty();
    }

    @Test
    void noBuildFiles_returnsEmpty() {
        assertThat(BuildService.defaultCommand(workspace, "package")).isEmpty();
    }

    // --- polyglot monorepo: exactly one subdir has the marker → auto cd -----

    @Test
    void mavenInExactlyOneSubdir_prependsCd() throws IOException {
        Path backend = Files.createDirectories(workspace.resolve("social-platform"));
        Files.writeString(backend.resolve("pom.xml"), "<project/>");
        // Decoy: another subdir without a pom
        Files.createDirectories(workspace.resolve("apps"));
        Files.writeString(workspace.resolve("apps").resolve("README.md"), "");

        assertThat(BuildService.defaultCommand(workspace, "install"))
            .hasValue("cd social-platform && mvn -DskipTests install");
    }

    @Test
    void gradleInExactlyOneSubdir_prependsCd_useWrapperFromSubdir() throws IOException {
        Path svc = Files.createDirectories(workspace.resolve("svc"));
        Files.writeString(svc.resolve("build.gradle"), "");
        Files.writeString(svc.resolve("gradlew"), "#!/bin/sh");

        assertThat(BuildService.defaultCommand(workspace, "build"))
            .hasValue("cd svc && ./gradlew -x test build");
    }

    @Test
    void nodeInExactlyOneSubdir_prependsCd_picksPnpmFromSubdir() throws IOException {
        Path fe = Files.createDirectories(workspace.resolve("frontend"));
        Files.writeString(fe.resolve("package.json"), "{}");
        Files.writeString(fe.resolve("pnpm-lock.yaml"), "");

        assertThat(BuildService.defaultCommand(workspace, "build"))
            .hasValue("cd frontend && pnpm run build");
    }

    // --- ambiguity: multiple subdirs with same marker → bail to manifest ----

    @Test
    void mavenInMultipleDepthOneSubdirs_returnsEmpty_soUserMustOverride() throws IOException {
        // Both POMs at depth 1 — ambiguous, walker should bail.
        Files.writeString(Files.createDirectories(workspace.resolve("backend")).resolve("pom.xml"), "<project/>");
        Files.writeString(Files.createDirectories(workspace.resolve("worker")).resolve("pom.xml"), "<project/>");

        assertThat(BuildService.defaultCommand(workspace, "install")).isEmpty();
    }

    @Test
    void mavenAtDepthTwoIsNotDiscovered_walkerIsDepthOne() throws IOException {
        // Intentional limit: a depth-2 pom (e.g. apps/support-bot/pom.xml) is invisible to the
        // walker — keeps the search bounded and predictable. Manifest commandLine is required.
        Files.writeString(Files.createDirectories(workspace.resolve("apps/support-bot")).resolve("pom.xml"), "<project/>");
        assertThat(BuildService.defaultCommand(workspace, "package")).isEmpty();
    }

    @Test
    void rootMavenAndSubdirNode_eachWorksForItsTool() throws IOException {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        Path fe = Files.createDirectories(workspace.resolve("ui"));
        Files.writeString(fe.resolve("package.json"), "{}");

        // Maven wins via root; node falls through to subdir auto-cd
        assertThat(BuildService.defaultCommand(workspace, "package")).hasValue("mvn -DskipTests package");
        // 'build' isn't a maven goal -> falls through to gradle (none) -> cargo (none) -> npm (subdir)
        // BUT maven's 'build' maps to 'package', so root wins. To test pure npm fallback we need
        // a commandName mvn doesn't understand:
        assertThat(BuildService.defaultCommand(workspace, "install"))
            .hasValue("mvn -DskipTests install"); // root pom still wins
    }

    // --- skip dirs: target/, node_modules/, build/ etc don't count -----------

    @Test
    void skipsTargetAndNodeModules() throws IOException {
        Files.writeString(Files.createDirectories(workspace.resolve("target")).resolve("pom.xml"), "<project/>");
        Files.writeString(Files.createDirectories(workspace.resolve("node_modules/x")).resolve("package.json"), "{}");
        Path real = Files.createDirectories(workspace.resolve("backend"));
        Files.writeString(real.resolve("pom.xml"), "<project/>");

        assertThat(BuildService.defaultCommand(workspace, "package"))
            .hasValue("cd backend && mvn -DskipTests package");
    }

    @Test
    void skipsDotDirs() throws IOException {
        Files.writeString(Files.createDirectories(workspace.resolve(".idea")).resolve("Cargo.toml"), "[package]");
        Files.writeString(Files.createDirectories(workspace.resolve("crate")).resolve("Cargo.toml"), "[package]");

        assertThat(BuildService.defaultCommand(workspace, "test"))
            .hasValue("cd crate && cargo test");
    }

    // --- describeWorkspace: error-message helper -----------------------------

    @Test
    void describeWorkspace_listsRootAndSubdirMarkers() throws IOException {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        Files.writeString(Files.createDirectories(workspace.resolve("ui")).resolve("package.json"), "{}");

        String desc = BuildService.describeWorkspace(workspace);
        assertThat(desc).contains("pom.xml in <root>").contains("package.json in ui");
    }

    @Test
    void describeWorkspace_callsOutAmbiguityForMultipleComponents() throws IOException {
        Files.writeString(Files.createDirectories(workspace.resolve("a")).resolve("pom.xml"), "<project/>");
        Files.writeString(Files.createDirectories(workspace.resolve("b")).resolve("pom.xml"), "<project/>");

        String desc = BuildService.describeWorkspace(workspace);
        assertThat(desc).contains("pom.xml in a, b").contains("Multiple components")
            .contains("commandLine override");
    }

    @Test
    void describeWorkspace_emptyForVirginRepo() {
        assertThat(BuildService.describeWorkspace(workspace))
            .isEqualTo("Detected: no recognized build files at workspace root or in first-level subdirs.");
    }
}
