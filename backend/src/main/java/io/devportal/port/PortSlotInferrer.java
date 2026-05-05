package io.devportal.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.devportal.manifest.Manifest;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Infers named port slots for an asset when its {@code devportal.yaml} is missing or has no
 * {@code spec.runtime.ports}. Sources, in priority order:
 * <ol>
 *   <li>Spring Boot config: {@code server.port}, {@code management.server.port}.</li>
 *   <li>{@code Dockerfile} {@code EXPOSE} directives (first → http, second → metrics, …).</li>
 *   <li>k8s {@code Service} or {@code containerPort} declarations (first → http, …).</li>
 * </ol>
 * Always returns a default {@code http} slot if nothing is detected, so the allocator never has
 * to refuse over a missing source.
 */
@Component
public class PortSlotInferrer {

    private static final Logger log = LoggerFactory.getLogger(PortSlotInferrer.class);

    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", "node_modules", "target", "build", "dist", ".gradle", ".idea", ".vscode", "vendor", "out"
    );

    private static final Pattern EXPOSE = Pattern.compile(
        "(?im)^\\s*EXPOSE\\s+(.+)$");
    private static final Pattern PROP_NUMBER = Pattern.compile("\\d+");

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public List<Manifest.Port> infer(Path workspace) {
        List<Manifest.Port> slots = new ArrayList<>();
        Set<String> usedNames = new LinkedHashSet<>();

        // ---- Spring Boot config ----
        Integer serverPort = findSpringProperty(workspace, "server.port");
        Integer mgmtPort = findSpringProperty(workspace, "management.server.port");
        if (serverPort != null) {
            slots.add(new Manifest.Port("http", "tcp", "http"));
            usedNames.add("http");
            log.debug("Inferred slot 'http' from server.port={}", serverPort);
        }
        if (mgmtPort != null && !mgmtPort.equals(serverPort)) {
            slots.add(new Manifest.Port("management", "tcp", "management"));
            usedNames.add("management");
            log.debug("Inferred slot 'management' from management.server.port={}", mgmtPort);
        }

        // ---- Dockerfile EXPOSE ----
        if (slots.isEmpty()) {
            for (int port : findDockerExposes(workspace)) {
                String name = pickName(usedNames, port);
                slots.add(new Manifest.Port(name, "tcp", purposeFor(name)));
                usedNames.add(name);
                log.debug("Inferred slot '{}' from Dockerfile EXPOSE {}", name, port);
            }
        }

        // ---- k8s manifests ----
        if (slots.isEmpty()) {
            for (int port : findK8sPorts(workspace)) {
                String name = pickName(usedNames, port);
                slots.add(new Manifest.Port(name, "tcp", purposeFor(name)));
                usedNames.add(name);
                log.debug("Inferred slot '{}' from k8s manifest port {}", name, port);
            }
        }

        // ---- Default ----
        if (slots.isEmpty()) {
            slots.add(new Manifest.Port("http", "tcp", "http"));
            log.debug("No port signals found; defaulting to single 'http' slot");
        }

        return slots;
    }

    /** Find a Spring property in any application.properties / application*.yml in the workspace. */
    private Integer findSpringProperty(Path ws, String key) {
        Integer found = null;
        try {
            for (Path p : findFiles(ws,
                List.of("application.properties", "application-default.properties"),
                List.of("application.yml", "application.yaml",
                        "application-default.yml", "application-default.yaml"))) {
                String name = p.getFileName().toString();
                if (name.endsWith(".properties")) {
                    Properties pr = new Properties();
                    try (var in = Files.newInputStream(p)) { pr.load(in); }
                    String v = pr.getProperty(key);
                    if (v != null) {
                        Integer i = parseInt(v);
                        if (i != null) return i;
                    }
                } else {
                    JsonNode root = yaml.readTree(p.toFile());
                    JsonNode node = root;
                    for (String segment : key.split("\\.")) {
                        if (node == null || !node.isObject()) { node = null; break; }
                        node = node.get(segment);
                    }
                    if (node != null && node.isInt()) return node.asInt();
                    if (node != null && node.isTextual()) {
                        Integer i = parseInt(node.asText());
                        if (i != null) return i;
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Spring property scan failed: {}", e.getMessage());
        }
        return found;
    }

    private List<Integer> findDockerExposes(Path ws) {
        List<Integer> out = new ArrayList<>();
        try {
            for (Path p : findByName(ws, n -> n.equals("Dockerfile") || n.startsWith("Dockerfile."))) {
                String text = Files.readString(p);
                Matcher m = EXPOSE.matcher(text);
                while (m.find()) {
                    for (String tok : m.group(1).trim().split("\\s+")) {
                        Matcher num = PROP_NUMBER.matcher(tok);
                        if (num.find()) {
                            Integer i = parseInt(num.group());
                            if (i != null && !out.contains(i)) out.add(i);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Dockerfile scan failed: {}", e.getMessage());
        }
        return out;
    }

    private List<Integer> findK8sPorts(Path ws) {
        List<Integer> out = new ArrayList<>();
        Set<Path> dirs = new LinkedHashSet<>();
        for (String hint : List.of("k8s", "deploy", "manifests", "kubernetes")) {
            Path d = ws.resolve(hint);
            if (Files.isDirectory(d)) dirs.add(d);
        }
        if (dirs.isEmpty()) return out;
        for (Path d : dirs) {
            try {
                Files.walkFileTree(d, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String n = file.getFileName().toString().toLowerCase();
                        if (!(n.endsWith(".yaml") || n.endsWith(".yml"))) return FileVisitResult.CONTINUE;
                        try {
                            for (JsonNode doc : yaml.readerForListOf(JsonNode.class)
                                    .<List<JsonNode>>readValue(file.toFile())) {
                                collectK8sPorts(doc, out);
                            }
                        } catch (Exception ignored) {
                            try {
                                JsonNode doc = yaml.readTree(file.toFile());
                                collectK8sPorts(doc, out);
                            } catch (IOException ignored2) {}
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException ignored) {}
        }
        return out;
    }

    private static void collectK8sPorts(JsonNode doc, List<Integer> out) {
        if (doc == null || !doc.isObject()) return;
        // Service .spec.ports[].port
        JsonNode ports = doc.path("spec").path("ports");
        if (ports.isArray()) {
            for (JsonNode p : ports) {
                int port = p.path("port").asInt(-1);
                if (port > 0 && !out.contains(port)) out.add(port);
            }
        }
        // PodSpec / Deployment .spec.template.spec.containers[].ports[].containerPort
        JsonNode containers = doc.path("spec").path("template").path("spec").path("containers");
        if (containers.isArray()) {
            for (JsonNode c : containers) {
                JsonNode cports = c.path("ports");
                if (cports.isArray()) {
                    for (JsonNode p : cports) {
                        int port = p.path("containerPort").asInt(-1);
                        if (port > 0 && !out.contains(port)) out.add(port);
                    }
                }
            }
        }
    }

    private List<Path> findFiles(Path ws, List<String>... groups) throws IOException {
        List<String> wanted = new ArrayList<>();
        for (List<String> g : groups) wanted.addAll(g);
        return findByName(ws, wanted::contains);
    }

    private static List<Path> findByName(Path ws, java.util.function.Predicate<String> match) throws IOException {
        List<Path> out = new ArrayList<>();
        Files.walkFileTree(ws, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(ws) && SKIP_DIRS.contains(name)) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (match.test(file.getFileName().toString())) out.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    private static Integer parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static String pickName(Set<String> used, int port) {
        // Heuristic: first slot is "http" if it's a typical web port, then numbered fallbacks.
        if (!used.contains("http") && (port == 80 || port == 8080 || port == 8081 || port == 3000)) return "http";
        if (!used.contains("metrics") && (port == 9090 || port == 9091 || port == 8082)) return "metrics";
        if (!used.contains("management") && (port == 8081 || port == 8082)) return "management";
        if (!used.contains("http")) return "http";
        // Generic "port-<n>" fallback when conventional names taken.
        String name = "port-" + port;
        int suffix = 2;
        while (used.contains(name)) name = "port-" + port + "-" + suffix++;
        return name;
    }

    private static String purposeFor(String name) {
        return switch (name) {
            case "http" -> "http";
            case "metrics" -> "metrics";
            case "management" -> "management";
            case "debug" -> "debug";
            default -> null;
        };
    }
}
