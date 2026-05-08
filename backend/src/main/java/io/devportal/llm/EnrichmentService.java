package io.devportal.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.devportal.asset.Asset;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.NotFoundException;
import io.devportal.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * LLM-powered suggestions for an asset's metadata. Every method is dry-run by default —
 * it returns the suggestion to the caller, who decides whether to apply it. Apply happens
 * via the existing PATCH /api/assets/{id} so workspace round-trips and validation stay
 * in one place.
 */
@Service
public class EnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentService.class);
    /** Cap the prompt around 12 KB worth of doc text so we don't OOM small models. */
    private static final int MAX_DOC_CHARS = 12_000;
    /** Doc files to consider, in priority order. */
    private static final List<String> CANDIDATE_DOCS = List.of(
            "README.md", "README.MD", "Readme.md", "readme.md",
            "ARCHITECTURE.md", "docs/ARCHITECTURE.md",
            "OVERVIEW.md", "docs/OVERVIEW.md",
            "ABOUT.md", "docs/ABOUT.md");

    private final OllamaClient ollama;
    private final WorkspaceService workspace;
    private final AssetRepository assets;
    private final ObjectMapper json = new ObjectMapper();

    public EnrichmentService(OllamaClient ollama, WorkspaceService workspace, AssetRepository assets) {
        this.ollama = ollama;
        this.workspace = workspace;
        this.assets = assets;
    }

    public record DescriptionSuggestion(String assetId, String current, String suggested, String docContext) {}
    public record TagSuggestion(String assetId, List<String> current, List<String> suggested, String docContext) {}

    public DescriptionSuggestion suggestDescription(String assetId, String model)
            throws IOException, InterruptedException {
        Asset asset = mustGet(assetId);
        String docs = collectDocs(assetId);
        String prompt = """
            You are a senior staff engineer summarizing a codebase for a developer portal.
            Read the provided repository documentation and write ONE short paragraph (2-3
            sentences, ~280 characters max) describing what this asset does. No marketing
            fluff — say what it builds, the language/runtime if obvious, and what it's for.
            Return JSON: {"description": "..."}.

            Asset id: %s
            Asset name: %s
            Asset type: %s
            Existing description: %s

            ---- documentation ----
            %s
            """.formatted(
                asset.id(),
                asset.name(),
                asset.type(),
                asset.description() == null ? "(none)" : asset.description(),
                docs.isBlank() ? "(no documentation found)" : docs);

        String raw = ollama.generate(model, prompt, true);
        String suggested = readJsonString(raw, "description");
        return new DescriptionSuggestion(assetId, asset.description(),
                suggested == null ? raw.trim() : suggested.trim(),
                contextHint(docs));
    }

    public TagSuggestion suggestTags(String assetId, String model)
            throws IOException, InterruptedException {
        Asset asset = mustGet(assetId);
        String docs = collectDocs(assetId);
        String prompt = """
            You are tagging a repository for a developer portal. Read the documentation and
            propose 3 to 8 short, lowercase, hyphenated tags that describe the technology
            stack, domain, and runtime (e.g. "spring-boot", "react", "postgres", "search",
            "ml"). Don't repeat existing tags unless they're clearly correct. Avoid generic
            words like "code" or "project". Return JSON: {"tags": ["a", "b", "c"]}.

            Asset id: %s
            Asset name: %s
            Existing tags: %s

            ---- documentation ----
            %s
            """.formatted(
                asset.id(),
                asset.name(),
                asset.tags() == null ? "[]" : asset.tags().toString(),
                docs.isBlank() ? "(no documentation found)" : docs);

        String raw = ollama.generate(model, prompt, true);
        List<String> suggested = readJsonStringArray(raw, "tags");
        // Normalize and merge with existing — caller can de-cherry-pick.
        Set<String> norm = new LinkedHashSet<>();
        for (String s : suggested) {
            String n = s == null ? null : s.toLowerCase().trim().replaceAll("\\s+", "-").replaceAll("[^a-z0-9-]", "");
            if (n != null && !n.isBlank()) norm.add(n);
        }
        return new TagSuggestion(assetId,
                asset.tags() == null ? List.of() : asset.tags(),
                new ArrayList<>(norm),
                contextHint(docs));
    }

    private Asset mustGet(String id) {
        return assets.findById(id).orElseThrow(() -> new NotFoundException("Asset '" + id + "' not found"));
    }

    /**
     * Walk the asset's workspace for the prioritized doc files; if none are found, fall back
     * to the first ~MAX_DOC_CHARS of any markdown at the repo root.
     */
    private String collectDocs(String assetId) {
        Path ws;
        try {
            ws = workspace.workspaceFor(assetId);
        } catch (Exception e) {
            return "";
        }
        if (!Files.isDirectory(ws)) return "";

        StringBuilder sb = new StringBuilder();
        for (String name : CANDIDATE_DOCS) {
            if (sb.length() >= MAX_DOC_CHARS) break;
            Path p = ws.resolve(name);
            if (Files.isRegularFile(p)) appendCapped(sb, p);
        }
        if (sb.length() == 0) {
            // Walk repo root for any *.md, take the first one or two.
            try (Stream<Path> stream = Files.list(ws)) {
                stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                        .limit(2)
                        .forEach(p -> { if (sb.length() < MAX_DOC_CHARS) appendCapped(sb, p); });
            } catch (IOException ignored) {}
        }
        return sb.toString();
    }

    private void appendCapped(StringBuilder sb, Path p) {
        try {
            String text = Files.readString(p);
            sb.append("\n\n# ").append(p.getFileName()).append("\n");
            int budget = MAX_DOC_CHARS - sb.length();
            if (budget <= 0) return;
            sb.append(text.length() > budget ? text.substring(0, budget) : text);
        } catch (IOException e) {
            log.debug("Could not read {}: {}", p, e.getMessage());
        }
    }

    private String contextHint(String docs) {
        if (docs.isBlank()) return "(no docs found in workspace)";
        return docs.length() + " chars of doc context";
    }

    private String readJsonString(String raw, String field) {
        try {
            JsonNode n = json.readTree(raw);
            JsonNode v = n.path(field);
            return v.isMissingNode() || v.isNull() ? null : v.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> readJsonStringArray(String raw, String field) {
        try {
            JsonNode n = json.readTree(raw);
            JsonNode arr = n.path(field);
            if (!arr.isArray()) return List.of();
            List<String> out = new ArrayList<>();
            for (JsonNode v : arr) out.add(v.asText());
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}
