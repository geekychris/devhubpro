package io.devportal.discover;

import io.devportal.discover.dto.DiscoveredResources;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Walks a workspace and identifies docker, k8s, helm, and language signals. */
@Component
public class ResourceDetector {

    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", "node_modules", "target", "build", "dist", ".gradle", ".idea", ".vscode",
        "vendor", "out", ".mvn"
    );

    private static final Set<String> K8S_HINT_DIRS = Set.of("k8s", "deploy", "manifests", "kubernetes");

    public DiscoveredResources scan(Path workspace) throws IOException {
        List<String> dockerfiles = new ArrayList<>();
        List<String> composes = new ArrayList<>();
        List<String> k8s = new ArrayList<>();
        List<String> helm = new ArrayList<>();
        List<String> kustomize = new ArrayList<>();

        // Quick check for hint directories at root.
        for (String hint : K8S_HINT_DIRS) {
            if (Files.isDirectory(workspace.resolve(hint))) k8s.add(hint + "/");
        }

        Files.walkFileTree(workspace, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(workspace) && SKIP_DIRS.contains(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                String rel = workspace.relativize(file).toString();
                switch (name) {
                    case "Dockerfile" -> dockerfiles.add(rel);
                    case "docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml" ->
                        composes.add(rel);
                    case "Chart.yaml" -> helm.add(workspace.relativize(file.getParent()).toString());
                    case "kustomization.yaml", "kustomization.yml" -> kustomize.add(rel);
                    default -> {
                        if (name.startsWith("Dockerfile.")) dockerfiles.add(rel);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return new DiscoveredResources(
            dockerfiles, composes, k8s, helm, kustomize,
            detectLanguage(workspace)
        );
    }

    /** Best-guess from build files. */
    private String detectLanguage(Path workspace) {
        if (Files.exists(workspace.resolve("pom.xml"))) return "java";
        if (Files.exists(workspace.resolve("build.gradle.kts"))) return "kotlin";
        if (Files.exists(workspace.resolve("build.gradle"))) return "java";
        if (Files.exists(workspace.resolve("Cargo.toml"))) return "rust";
        if (Files.exists(workspace.resolve("go.mod"))) return "go";
        if (Files.exists(workspace.resolve("package.json"))) return "typescript";
        if (Files.exists(workspace.resolve("pyproject.toml")) || Files.exists(workspace.resolve("setup.py"))) {
            return "python";
        }
        return null;
    }
}
