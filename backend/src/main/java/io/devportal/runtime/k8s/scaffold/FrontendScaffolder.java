package io.devportal.runtime.k8s.scaffold;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.devportal.asset.Asset;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Detects React / Vite / Next / CRA tiers nested inside an asset's repo and scaffolds the
 * Dockerfile + nginx.conf + k8s manifest needed to ship them. Designed for the common
 * "Spring backend with a sibling React UI" layout (e.g. social-platform/social-frontend).
 *
 * <p>Detection walks the workspace up to depth 4 and reads each {@code package.json}, checking
 * its {@code dependencies} / {@code devDependencies} for known frontend frameworks. For each
 * detected tier we infer the build command (vite vs CRA vs next) and the static-output dir.
 *
 * <p>Scaffolding writes:
 * <ul>
 *   <li>{@code Dockerfile} — multi-stage: node:20-alpine builder runs {@code npm ci && npm run build},
 *       nginx:alpine runtime serves the static output.</li>
 *   <li>{@code nginx.conf} — single-page-app routing ({@code try_files $uri /index.html}).</li>
 *   <li>{@code k8s/<asset>-<tier>.yaml} — Deployment + NodePort Service. Pinned NodePort
 *       comes from a small allocator that picks the next free port in 30100–30199 (avoids
 *       clashing with the existing port-registry pool 30000–32767 reserved for slot=http).</li>
 * </ul>
 */
@Component
public class FrontendScaffolder {

    private static final Logger log = LoggerFactory.getLogger(FrontendScaffolder.class);

    /** Subdirs we never recurse into when hunting for tiers. */
    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", "node_modules", "target", "build", "dist", ".next", ".gradle",
        ".idea", ".vscode", "out", ".mvn", "vendor"
    );

    /** Frontend framework markers (any in dependencies or devDependencies flips the tier on). */
    private static final Set<String> FRAMEWORK_DEPS = Set.of(
        "react", "react-dom", "react-scripts",
        "vite", "@vitejs/plugin-react",
        "next",
        "vue", "@vue/cli-service",
        "@angular/core"
    );

    private static final ObjectMapper json = new ObjectMapper();

    /** One detected frontend tier in an asset's workspace. */
    public record Tier(
        // Repo-relative path to the tier directory (e.g. "social-platform/social-frontend").
        String relPath,
        // Vite / CRA / Next / Generic — drives the output dir + build script defaults.
        Framework framework,
        // The output dir relative to {@code relPath} (e.g. "dist", "build", ".next").
        String outputDir,
        // The npm/pnpm/yarn script that produces a static bundle. Defaults to "build".
        String buildScript,
        // Whether a Dockerfile exists under {@code relPath}.
        boolean hasDockerfile,
        // Lockfile flavor: npm | pnpm | yarn — chooses the build base image.
        String packageManager
    ) {}

    public enum Framework { VITE, CRA, NEXT, VUE_CLI, ANGULAR, GENERIC_REACT }

    public record ScaffoldResult(
        String tierPath,
        boolean dockerfileWritten,
        boolean nginxConfWritten,
        boolean k8sWritten,
        // True when the asset's devportal.yaml's spec.docker.images was extended with the new tag.
        // False when the manifest was already up to date or couldn't be safely edited.
        boolean manifestUpdated,
        // Why we couldn't update the manifest (e.g. missing manifest, no spec.docker block).
        // Null when manifestUpdated=true, or when there was nothing to do.
        String manifestNote,
        String imageTag,
        int nodePort,
        List<String> filesWritten,
        String message
    ) {}

    /** Walk the workspace and return every frontend tier we find. */
    public List<Tier> detectTiers(Path workspace) {
        List<Tier> tiers = new ArrayList<>();
        if (!Files.isDirectory(workspace)) return tiers;
        try {
            Files.walkFileTree(workspace, Set.of(), 4, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(workspace) && SKIP_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!"package.json".equals(file.getFileName().toString())) return FileVisitResult.CONTINUE;
                    Tier t = parseTier(workspace, file);
                    if (t != null) tiers.add(t);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.debug("frontend tier scan failed for {}: {}", workspace, e.getMessage());
        }
        return tiers;
    }

    private Tier parseTier(Path workspace, Path pkgJsonPath) {
        try {
            JsonNode pkg = json.readTree(Files.readString(pkgJsonPath));
            // Combine dependencies + devDependencies and check for any framework marker.
            Framework fw = detectFramework(pkg);
            if (fw == null) return null;

            Path tierDir = pkgJsonPath.getParent();
            String relPath = workspace.relativize(tierDir).toString();
            if (relPath.isEmpty()) relPath = ".";

            // Build script: prefer "build" or the framework default; fall back to "build".
            String buildScript = "build";
            JsonNode scripts = pkg.path("scripts");
            if (scripts.isObject()) {
                if (scripts.has("build")) buildScript = "build";
                else if (fw == Framework.NEXT && scripts.has("build")) buildScript = "build";
            }

            String outDir = switch (fw) {
                case VITE -> "dist";
                case CRA, GENERIC_REACT -> "build";
                case NEXT -> ".next";
                case VUE_CLI -> "dist";
                case ANGULAR -> "dist";
            };

            String pm = "npm";
            if (Files.exists(tierDir.resolve("pnpm-lock.yaml"))) pm = "pnpm";
            else if (Files.exists(tierDir.resolve("yarn.lock"))) pm = "yarn";

            boolean hasDockerfile = Files.exists(tierDir.resolve("Dockerfile"));
            return new Tier(relPath, fw, outDir, buildScript, hasDockerfile, pm);
        } catch (IOException e) {
            return null;
        }
    }

    private Framework detectFramework(JsonNode pkg) {
        if (hasDep(pkg, "vite") || hasDep(pkg, "@vitejs/plugin-react")) return Framework.VITE;
        if (hasDep(pkg, "next")) return Framework.NEXT;
        if (hasDep(pkg, "react-scripts")) return Framework.CRA;
        if (hasDep(pkg, "@vue/cli-service") || hasDep(pkg, "vue")) return Framework.VUE_CLI;
        if (hasDep(pkg, "@angular/core")) return Framework.ANGULAR;
        if (hasDep(pkg, "react")) return Framework.GENERIC_REACT;
        return null;
    }

    private static boolean hasDep(JsonNode pkg, String name) {
        return pkg.path("dependencies").has(name) || pkg.path("devDependencies").has(name);
    }

    /**
     * Scaffold artifacts for one tier. Existing files are NOT overwritten — return
     * {@code dockerfileWritten=false} etc. so the caller can show "already present".
     *
     * @param k8sDir directory where Deployment+Service yaml lives (typically {@code workspace/k8s})
     * @param nodePort port to pin in the Service's nodePort field (caller picks a free one)
     */
    public ScaffoldResult scaffold(Asset asset, Path workspace, Tier tier, Path k8sDir, int nodePort)
            throws IOException {
        Path tierAbs = workspace.resolve(tier.relPath());
        if (!Files.isDirectory(tierAbs)) {
            throw new IOException("Tier path does not exist: " + tierAbs);
        }
        List<String> wrote = new ArrayList<>();

        Path dockerfile = tierAbs.resolve("Dockerfile");
        boolean dockerfileWritten = false;
        if (!Files.exists(dockerfile)) {
            Files.writeString(dockerfile, dockerfileFor(tier));
            wrote.add(workspace.relativize(dockerfile).toString());
            dockerfileWritten = true;
        }

        Path nginxConf = tierAbs.resolve("nginx.conf");
        boolean nginxWritten = false;
        // CRA / Vite / Generic React all use the SPA fallback. Next handles its own runtime,
        // so we skip nginx.conf for it (the Dockerfile uses node:20-alpine runtime instead).
        if (tier.framework() != Framework.NEXT && !Files.exists(nginxConf)) {
            Files.writeString(nginxConf, nginxConfContent());
            wrote.add(workspace.relativize(nginxConf).toString());
            nginxWritten = true;
        }

        // K8s manifest. Written into the asset's k8s/ tree using a deterministic filename so
        // re-running scaffolder is idempotent. Image tag mirrors what the docker.images entry
        // will declare: worksphere/<asset>-<tier-leaf>:latest by default.
        String tierLeaf = tier.relPath().replace('/', '-');
        if (".".equals(tier.relPath())) tierLeaf = asset.id();
        String imageTag = "worksphere/" + asset.id() + "-" + tierLeaf + ":latest";
        // Special-case: if the asset is enterprise-social-platform AND the tier leaf is
        // social-frontend, use the historical worksphere/frontend tag so existing manifests work.
        if ("enterprise-social-platform".equals(asset.id()) && tier.relPath().endsWith("social-frontend")) {
            imageTag = "worksphere/frontend:latest";
        }

        String k8sFileName = (asset.id() + "-" + tierLeaf).replace('/', '-') + ".yaml";
        Path k8sFile = k8sDir.resolve(k8sFileName);
        boolean k8sWritten = false;
        if (!Files.exists(k8sFile)) {
            Files.createDirectories(k8sDir);
            Files.writeString(k8sFile, k8sManifest(asset.id(), tierLeaf, imageTag, nodePort, tier));
            wrote.add(workspace.relativize(k8sFile).toString());
            k8sWritten = true;
        }

        // Try to extend devportal.yaml's spec.docker.images with the new entry. Idempotent — if
        // the tag is already declared we skip. Falls back to a note when the manifest is missing
        // or doesn't have a spec.docker block.
        String tierDockerfile = tier.relPath() + "/Dockerfile";
        String tierContext = tier.relPath();
        ManifestUpdate mu = addDockerImage(workspace, imageTag, tierDockerfile, tierContext);
        if (mu.applied) {
            wrote.add("devportal.yaml");
        }

        return new ScaffoldResult(
            tier.relPath(), dockerfileWritten, nginxWritten, k8sWritten,
            mu.applied, mu.note,
            imageTag, nodePort, wrote,
            wrote.isEmpty() ? "All files already present; nothing scaffolded."
                : "Wrote " + wrote.size() + " file(s) for " + tier.framework() + " tier"
        );
    }

    private record ManifestUpdate(boolean applied, String note) {}

    /**
     * Append a new {@code {tag, dockerfile, context}} entry to {@code spec.docker.images} in the
     * asset's {@code devportal.yaml}. Implemented as text insertion (not a full YAML re-serialize)
     * so the user's comments and formatting are preserved.
     *
     * <p>Handles three shapes:
     * <ol>
     *   <li>{@code spec.docker.images:} exists with at least one entry — append a new sibling at
     *       the end of the list using the same indentation level as existing entries.</li>
     *   <li>{@code spec.docker:} exists but no {@code images:} — insert a fresh {@code images:}
     *       sub-block right after the last existing docker property.</li>
     *   <li>No {@code spec.docker:} at all — return a note. The user must add it manually.</li>
     * </ol>
     * <p>If the tag is already declared anywhere under {@code spec.docker.images}, returns
     * {@code applied=false} with a "already declared" note.
     */
    private ManifestUpdate addDockerImage(Path workspace, String tag, String dockerfile, String context) {
        Path manifestPath = workspace.resolve("devportal.yaml");
        if (!Files.exists(manifestPath)) {
            return new ManifestUpdate(false, "no devportal.yaml at workspace root — add the image to spec.docker.images manually");
        }
        String content;
        try { content = Files.readString(manifestPath); }
        catch (IOException e) { return new ManifestUpdate(false, "could not read devportal.yaml: " + e.getMessage()); }

        // Idempotency check: tag already declared?
        if (content.contains("tag: " + tag) || content.contains("tag: \"" + tag + "\"")
            || content.contains("tag: '" + tag + "'")) {
            return new ManifestUpdate(false, "tag already declared in spec.docker.images");
        }

        String[] lines = content.split("\n", -1);
        // Find the spec.docker block and (within it) optionally the images: list.
        int dockerLineIdx = -1, dockerIndent = -1;
        int imagesLineIdx = -1, imagesIndent = -1;
        int nextSiblingOfDocker = lines.length; // first line at <= docker indent after dockerLineIdx
        int lastImagesEntryEnd = -1;             // line idx of the last property line of the last image entry
        int imagesEntryIndent = -1;              // indent of the "- tag:" hyphen
        boolean inDocker = false, inImages = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int indent = leadingSpaces(line);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            if (!inDocker) {
                // Detect "  docker:" (2-space indent inside spec:, but we don't bind to a fixed
                // depth — accept any line whose trimmed text is "docker:" inside a 'spec:' parent).
                if (trimmed.equals("docker:")) {
                    dockerLineIdx = i;
                    dockerIndent = indent;
                    inDocker = true;
                }
                continue;
            }
            // We're inside docker. As soon as we see a line at <= dockerIndent that's a key, we left it.
            if (indent <= dockerIndent && !trimmed.startsWith("-")) {
                nextSiblingOfDocker = i;
                break;
            }
            if (!inImages) {
                if (trimmed.equals("images:") && indent > dockerIndent) {
                    imagesLineIdx = i;
                    imagesIndent = indent;
                    inImages = true;
                    continue;
                }
                continue;
            }
            // Inside images: list. Each entry starts with a "- tag:" line; subsequent property
            // lines belong to the same entry until the next "-" at the same indent.
            if (indent <= imagesIndent && !trimmed.startsWith("-")) {
                // left images block while still inside docker
                inImages = false;
                continue;
            }
            if (trimmed.startsWith("-")) {
                if (imagesEntryIndent < 0) imagesEntryIndent = indent;
                lastImagesEntryEnd = i;
            } else if (lastImagesEntryEnd >= 0 && indent > imagesEntryIndent) {
                lastImagesEntryEnd = i; // continued property of the current entry
            }
        }

        if (dockerLineIdx < 0) {
            return new ManifestUpdate(false, "no spec.docker block in devportal.yaml — add it and re-run, or add the image entry manually");
        }

        StringBuilder out = new StringBuilder();
        if (imagesLineIdx >= 0 && lastImagesEntryEnd >= 0) {
            // Case 1: append a sibling under existing images list.
            String entryIndent = " ".repeat(imagesEntryIndent);          // for the leading "- "
            String propIndent = " ".repeat(imagesEntryIndent + 2);       // for tag/dockerfile/context
            for (int i = 0; i <= lastImagesEntryEnd; i++) out.append(lines[i]).append('\n');
            out.append(entryIndent).append("- tag: ").append(tag).append('\n');
            out.append(propIndent).append("dockerfile: ").append(dockerfile).append('\n');
            out.append(propIndent).append("context: ").append(context).append('\n');
            for (int i = lastImagesEntryEnd + 1; i < lines.length; i++) {
                out.append(lines[i]);
                if (i < lines.length - 1) out.append('\n');
            }
        } else if (imagesLineIdx >= 0) {
            // Case 1b: empty images list (just "images:" with nothing under it). Use 2x indent of
            // images key as the entry indent.
            String entryIndent = " ".repeat(imagesIndent + 2);
            String propIndent = " ".repeat(imagesIndent + 4);
            for (int i = 0; i <= imagesLineIdx; i++) out.append(lines[i]).append('\n');
            out.append(entryIndent).append("- tag: ").append(tag).append('\n');
            out.append(propIndent).append("dockerfile: ").append(dockerfile).append('\n');
            out.append(propIndent).append("context: ").append(context).append('\n');
            for (int i = imagesLineIdx + 1; i < lines.length; i++) {
                out.append(lines[i]);
                if (i < lines.length - 1) out.append('\n');
            }
        } else {
            // Case 2: docker: block with no images: yet. Insert images: right after dockerLineIdx
            // and before nextSiblingOfDocker.
            String imagesIndentStr = " ".repeat(dockerIndent + 2);
            String entryIndentStr = " ".repeat(dockerIndent + 4);
            String propIndentStr = " ".repeat(dockerIndent + 6);
            for (int i = 0; i < nextSiblingOfDocker; i++) out.append(lines[i]).append('\n');
            out.append(imagesIndentStr).append("images:\n");
            out.append(entryIndentStr).append("- tag: ").append(tag).append('\n');
            out.append(propIndentStr).append("dockerfile: ").append(dockerfile).append('\n');
            out.append(propIndentStr).append("context: ").append(context).append('\n');
            for (int i = nextSiblingOfDocker; i < lines.length; i++) {
                out.append(lines[i]);
                if (i < lines.length - 1) out.append('\n');
            }
        }

        try {
            Files.writeString(manifestPath, out.toString());
            return new ManifestUpdate(true, null);
        } catch (IOException e) {
            return new ManifestUpdate(false, "writeString failed: " + e.getMessage());
        }
    }

    private static int leadingSpaces(String s) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == ' ') n++;
        return n;
    }

    // ---------- file content ----------

    private static String dockerfileFor(Tier t) {
        if (t.framework() == Framework.NEXT) return nextDockerfile(t);
        return spaDockerfile(t);
    }

    /** Multi-stage SPA Dockerfile: build with node, serve with nginx. */
    private static String spaDockerfile(Tier t) {
        String installCmd = switch (t.packageManager()) {
            case "pnpm" -> "RUN corepack enable && pnpm install --frozen-lockfile";
            case "yarn" -> "RUN corepack enable && yarn install --frozen-lockfile";
            default     -> "RUN npm ci";
        };
        String buildCmd = switch (t.packageManager()) {
            case "pnpm" -> "RUN pnpm run " + t.buildScript();
            case "yarn" -> "RUN yarn run " + t.buildScript();
            default     -> "RUN npm run " + t.buildScript();
        };
        return ""
            + "# " + t.framework() + " single-page app — multi-stage: node builder + nginx runtime.\n"
            + "FROM node:20-alpine AS builder\n"
            + "WORKDIR /src\n"
            + "COPY package.json " + (t.packageManager().equals("pnpm") ? "pnpm-lock.yaml" : t.packageManager().equals("yarn") ? "yarn.lock" : "package-lock.json") + "* ./\n"
            + installCmd + "\n"
            + "COPY . .\n"
            + buildCmd + "\n"
            + "\n"
            + "FROM nginx:alpine\n"
            + "COPY nginx.conf /etc/nginx/conf.d/default.conf\n"
            + "COPY --from=builder /src/" + t.outputDir() + " /usr/share/nginx/html\n"
            + "EXPOSE 80\n"
            + "CMD [\"nginx\", \"-g\", \"daemon off;\"]\n";
    }

    /** Next.js standalone runtime — node, not nginx, because Next has SSR routes. */
    private static String nextDockerfile(Tier t) {
        return ""
            + "# Next.js — node runtime (handles SSR routes that nginx can't).\n"
            + "FROM node:20-alpine AS builder\n"
            + "WORKDIR /src\n"
            + "COPY package.json package-lock.json* ./\n"
            + "RUN npm ci\n"
            + "COPY . .\n"
            + "RUN npm run build\n"
            + "\n"
            + "FROM node:20-alpine\n"
            + "WORKDIR /app\n"
            + "ENV NODE_ENV=production\n"
            + "COPY --from=builder /src/.next/standalone ./\n"
            + "COPY --from=builder /src/.next/static ./.next/static\n"
            + "COPY --from=builder /src/public ./public\n"
            + "EXPOSE 3000\n"
            + "CMD [\"node\", \"server.js\"]\n";
    }

    private static String nginxConfContent() {
        return ""
            + "server {\n"
            + "  listen 80;\n"
            + "  server_name _;\n"
            + "  root /usr/share/nginx/html;\n"
            + "  index index.html;\n"
            + "\n"
            + "  # Single-page app routing — let the client router resolve unknown paths.\n"
            + "  location / {\n"
            + "    try_files $uri $uri/ /index.html;\n"
            + "  }\n"
            + "\n"
            + "  # Long-cache hashed assets; index.html stays uncached.\n"
            + "  location ~* \\.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {\n"
            + "    expires 1y;\n"
            + "    add_header Cache-Control \"public, immutable\";\n"
            + "  }\n"
            + "}\n";
    }

    private static String k8sManifest(String assetId, String tierLeaf, String imageTag,
                                      int nodePort, Tier t) {
        int targetPort = (t.framework() == Framework.NEXT) ? 3000 : 80;
        String name = assetId + "-" + tierLeaf;
        // Limit deployment label length to k8s 63-char cap.
        if (name.length() > 53) name = name.substring(0, 53);
        return ""
            + "# Auto-scaffolded by dev_portal FrontendScaffolder.\n"
            + "apiVersion: apps/v1\n"
            + "kind: Deployment\n"
            + "metadata:\n"
            + "  name: " + name + "\n"
            + "spec:\n"
            + "  replicas: 1\n"
            + "  selector:\n"
            + "    matchLabels:\n"
            + "      app: " + name + "\n"
            + "  template:\n"
            + "    metadata:\n"
            + "      labels:\n"
            + "        app: " + name + "\n"
            + "    spec:\n"
            + "      containers:\n"
            + "        - name: " + name + "\n"
            + "          image: " + imageTag + "\n"
            + "          imagePullPolicy: Never\n"
            + "          ports:\n"
            + "            - containerPort: " + targetPort + "\n"
            + "          resources:\n"
            + "            requests:\n"
            + "              memory: 64Mi\n"
            + "              cpu: 50m\n"
            + "            limits:\n"
            + "              memory: 128Mi\n"
            + "              cpu: 200m\n"
            + "          readinessProbe:\n"
            + "            httpGet:\n"
            + "              path: /\n"
            + "              port: " + targetPort + "\n"
            + "            initialDelaySeconds: 5\n"
            + "            periodSeconds: 5\n"
            + "---\n"
            + "apiVersion: v1\n"
            + "kind: Service\n"
            + "metadata:\n"
            + "  name: " + name + "\n"
            + "spec:\n"
            + "  type: NodePort\n"
            + "  selector:\n"
            + "    app: " + name + "\n"
            + "  ports:\n"
            + "    - name: web\n"
            + "      port: 80\n"
            + "      targetPort: " + targetPort + "\n"
            + "      nodePort: " + nodePort + "\n";
    }
}
