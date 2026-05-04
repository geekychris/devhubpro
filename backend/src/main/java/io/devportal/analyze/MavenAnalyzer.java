package io.devportal.analyze;

import io.devportal.analyze.dto.MavenAnalysis;
import io.devportal.analyze.dto.MavenCoord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Parses Maven {@code pom.xml} files in a workspace, including multi-module aggregators.
 *
 * <p>Resolves simple {@code ${prop}} references against {@code <properties>}, plus a few
 * built-ins like {@code ${project.version}}. Does not invoke Maven; will not pick up
 * super-pom inheritance, transitive deps, or BOM-managed versions. That's fine for
 * "what does this repo declare" — we're not building a resolver.
 */
@Component
public class MavenAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(MavenAnalyzer.class);

    public boolean hasPom(Path workspace) {
        return Files.exists(workspace.resolve("pom.xml"));
    }

    public MavenAnalysis analyze(Path workspace) throws IOException {
        List<MavenCoord> published = new ArrayList<>();
        List<MavenCoord> deps = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        analyzePom(workspace, ".", published, deps, warnings, 0);
        return new MavenAnalysis(published, deps, warnings);
    }

    private void analyzePom(Path workspace, String relPath,
                            List<MavenCoord> published,
                            List<MavenCoord> deps,
                            List<String> warnings,
                            int depth) throws IOException {
        if (depth > 6) {
            warnings.add("Recursion limit hit at " + relPath);
            return;
        }
        Path pomPath = workspace.resolve(relPath).resolve("pom.xml");
        if (!Files.exists(pomPath)) return;

        Document doc = parse(pomPath, warnings);
        if (doc == null) return;

        Element project = doc.getDocumentElement();

        // Inherit groupId/version from <parent> if not specified locally.
        Element parent = firstChild(project, "parent");
        String parentGroupId = parent == null ? null : text(firstChild(parent, "groupId"));
        String parentVersion = parent == null ? null : text(firstChild(parent, "version"));

        String groupId = orElse(text(firstChild(project, "groupId")), parentGroupId);
        String artifactId = text(firstChild(project, "artifactId"));
        String version = orElse(text(firstChild(project, "version")), parentVersion);

        Map<String, String> props = new HashMap<>();
        if (groupId != null) props.put("project.groupId", groupId);
        if (version != null) props.put("project.version", version);
        Element propsEl = firstChild(project, "properties");
        if (propsEl != null) {
            NodeList kids = propsEl.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                Node n = kids.item(i);
                if (n instanceof Element e) {
                    props.put(e.getTagName(), text(e));
                }
            }
        }

        if (artifactId != null) {
            published.add(new MavenCoord(
                substitute(groupId, props),
                substitute(artifactId, props),
                substitute(version, props),
                relPath
            ));
        }

        // Direct dependencies (skip dependencyManagement entries — those are version mgmt only).
        Element depsEl = firstChild(project, "dependencies");
        if (depsEl != null) {
            for (Element d : children(depsEl, "dependency")) {
                String g = substitute(text(firstChild(d, "groupId")), props);
                String a = substitute(text(firstChild(d, "artifactId")), props);
                String v = substitute(text(firstChild(d, "version")), props);
                String scope = text(firstChild(d, "scope"));
                if ("test".equals(scope)) continue; // skip test-scoped
                if (a == null) continue;
                deps.add(new MavenCoord(g, a, v, relPath));
            }
        }

        // Recurse into modules.
        Element modulesEl = firstChild(project, "modules");
        if (modulesEl != null) {
            for (Element m : children(modulesEl, "module")) {
                String mod = text(m);
                if (mod == null || mod.isBlank()) continue;
                String childRel = relPath.equals(".") ? mod : relPath + "/" + mod;
                analyzePom(workspace, childRel, published, deps, warnings, depth + 1);
            }
        }
    }

    private Document parse(Path pom, List<String> warnings) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder b = f.newDocumentBuilder();
            return b.parse(pom.toFile());
        } catch (ParserConfigurationException | SAXException e) {
            warnings.add("Failed to parse " + pom + ": " + e.getMessage());
            log.warn("pom parse error: {}", e.getMessage());
            return null;
        }
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n instanceof Element e && e.getTagName().equals(tag)) return e;
        }
        return null;
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n instanceof Element e && e.getTagName().equals(tag)) out.add(e);
        }
        return out;
    }

    private static String text(Element e) {
        if (e == null) return null;
        String t = e.getTextContent();
        return t == null ? null : t.trim();
    }

    private static String orElse(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    /** Replace ${foo} references using {@code props}; leaves unknown refs in place. */
    private static String substitute(String value, Map<String, String> props) {
        if (value == null) return null;
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < value.length()) {
            int start = value.indexOf("${", i);
            if (start < 0) { out.append(value, i, value.length()); break; }
            int end = value.indexOf('}', start);
            if (end < 0) { out.append(value, i, value.length()); break; }
            out.append(value, i, start);
            String key = value.substring(start + 2, end);
            String resolved = props.get(key);
            if (resolved != null) out.append(resolved);
            else out.append(value, start, end + 1);
            i = end + 1;
        }
        return out.toString();
    }
}
