package io.devportal.manifest;

import java.util.ArrayList;
import java.util.List;

/**
 * Targeted text edits to {@code devportal.yaml} that add or remove the
 * {@code spec.runtime.proxy} block while preserving comments, ordering, and
 * formatting of every other section.
 *
 * <p>Assumes 2-space block-style indentation (the convention used by every
 * portal-managed manifest). Flow-style {@code proxy: {...}} entries are not
 * matched — if the existing file uses flow style, the operation is a no-op
 * and callers must edit by hand.
 */
public final class ManifestProxyEditor {

    private ManifestProxyEditor() {}

    /**
     * Insert (or replace) {@code spec.runtime.proxy} in {@code rawYaml}. Returns the new file
     * contents. Throws {@link IllegalStateException} when the file has no {@code spec:} block,
     * which is impossible for a schema-valid manifest.
     */
    public static String setProxy(String rawYaml, String path, String portSlot,
                                  boolean stripPrefix, String host) {
        boolean hasPath = path != null && !path.isBlank();
        boolean hasHost = host != null && !host.isBlank();
        if (!hasPath && !hasHost) {
            throw new IllegalArgumentException("at least one of path or host is required");
        }
        if (portSlot == null || portSlot.isBlank()) throw new IllegalArgumentException("portSlot is required");

        String eol = detectEol(rawYaml);
        List<String> lines = splitLines(rawYaml);
        List<String> proxyBlock = renderProxyBlock(path, portSlot, stripPrefix, host);

        int specStart = findTopLevelKey(lines, "spec:");
        if (specStart < 0) throw new IllegalStateException("Manifest has no spec: section");
        int specEnd = endOfBlock(lines, specStart, 0);

        int runtimeLine = findChildKey(lines, specStart + 1, specEnd, 2, "runtime:");
        if (runtimeLine < 0) {
            // No runtime block — insert a fresh runtime: with proxy: under spec.
            int insertAt = trimTrailingBlanks(lines, specStart + 1, specEnd);
            List<String> ins = new ArrayList<>();
            ins.add("  runtime:");
            for (String ln : proxyBlock) ins.add("  " + ln); // re-indent proxy block under runtime
            lines.addAll(insertAt, ins);
        } else {
            int runtimeEnd = endOfBlock(lines, runtimeLine, 2);
            int proxyLine = findChildKey(lines, runtimeLine + 1, runtimeEnd, 4, "proxy:");
            if (proxyLine < 0) {
                int insertAt = trimTrailingBlanks(lines, runtimeLine + 1, runtimeEnd);
                lines.addAll(insertAt, proxyBlock);
            } else {
                int proxyEnd = endOfBlock(lines, proxyLine, 4);
                lines.subList(proxyLine, proxyEnd).clear();
                lines.addAll(proxyLine, proxyBlock);
            }
        }
        return String.join(eol, lines);
    }

    /**
     * Remove {@code spec.runtime.proxy} from {@code rawYaml}. If the proxy block isn't present
     * (or isn't in block style), the input is returned unchanged.
     */
    public static String removeProxy(String rawYaml) {
        String eol = detectEol(rawYaml);
        List<String> lines = splitLines(rawYaml);

        int specStart = findTopLevelKey(lines, "spec:");
        if (specStart < 0) return rawYaml;
        int specEnd = endOfBlock(lines, specStart, 0);

        int runtimeLine = findChildKey(lines, specStart + 1, specEnd, 2, "runtime:");
        if (runtimeLine < 0) return rawYaml;
        int runtimeEnd = endOfBlock(lines, runtimeLine, 2);

        int proxyLine = findChildKey(lines, runtimeLine + 1, runtimeEnd, 4, "proxy:");
        if (proxyLine < 0) return rawYaml;
        int proxyEnd = endOfBlock(lines, proxyLine, 4);

        lines.subList(proxyLine, proxyEnd).clear();
        return String.join(eol, lines);
    }

    private static List<String> renderProxyBlock(String path, String portSlot,
                                                 boolean stripPrefix, String host) {
        List<String> ls = new ArrayList<>();
        ls.add("    proxy:");
        if (path != null && !path.isBlank()) ls.add("      path: " + yamlScalar(path));
        ls.add("      portSlot: " + yamlScalar(portSlot));
        // Only emit stripPrefix when non-default (false) so the file stays minimal.
        if (!stripPrefix) ls.add("      stripPrefix: false");
        if (host != null && !host.isBlank()) ls.add("      host: " + yamlScalar(host));
        return ls;
    }

    /** Quote scalars containing YAML-significant characters; otherwise emit bare. */
    private static String yamlScalar(String s) {
        if (s == null) return "\"\"";
        if (s.matches("^[A-Za-z0-9._/-]+$")) return s;
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Find the first line that is exactly {@code key} at indent 0 (top-level). */
    private static int findTopLevelKey(List<String> lines, String key) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).equals(key)) return i;
        }
        return -1;
    }

    /**
     * Within {@code [from, to)}, find the first line that is exactly {@code key} preceded by
     * {@code indent} spaces. Returns the index, or -1.
     */
    private static int findChildKey(List<String> lines, int from, int to, int indent, String key) {
        String prefix = " ".repeat(indent);
        String target = prefix + key;
        for (int i = from; i < to; i++) {
            if (lines.get(i).equals(target)) return i;
        }
        return -1;
    }

    /**
     * Return the exclusive end index of the YAML block whose header is at {@code headerLine} and
     * whose header indent is {@code parentIndent}. The block includes every following line that
     * is blank, more deeply indented than {@code parentIndent}, or fully indented to a depth
     * greater than {@code parentIndent}. Stops at the next sibling/parent key or EOF.
     */
    private static int endOfBlock(List<String> lines, int headerLine, int parentIndent) {
        int childIndentMin = parentIndent + 1; // any line at this indent or deeper belongs to the block
        for (int i = headerLine + 1; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.isEmpty()) continue;
            int leading = leadingSpaces(l);
            if (leading < childIndentMin) return i;
        }
        return lines.size();
    }

    /**
     * Return {@code end} pulled back over any trailing blank lines in {@code [start, end)}, so
     * an insertion lands before the blank lines that visually separate this block from the next.
     */
    private static int trimTrailingBlanks(List<String> lines, int start, int end) {
        int i = end;
        while (i > start && i - 1 < lines.size() && lines.get(i - 1).isBlank()) i--;
        return i;
    }

    private static int leadingSpaces(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') n++;
        return n;
    }

    /** Detect the dominant line ending in the source so we round-trip it. */
    private static String detectEol(String s) {
        return s.contains("\r\n") ? "\r\n" : "\n";
    }

    /** Split into lines preserving empty trailing entry (mirrors text editors' notion of line count). */
    private static List<String> splitLines(String s) {
        // -1 keeps trailing empty fields, so a file ending in \n yields a trailing "" we re-emit.
        String normalized = s.replace("\r\n", "\n");
        return new ArrayList<>(java.util.Arrays.asList(normalized.split("\n", -1)));
    }
}
