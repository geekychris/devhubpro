package io.devportal.cli.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Tiny output helpers. Commands use {@link #table}, {@link #json}, or {@link #yaml} —
 * {@code --format=json|yaml|table} on each command picks a renderer; {@code table} is the
 * default for human use.
 */
public final class Out {

    public enum Format { TABLE, JSON, YAML }

    /**
     * Layout hint for {@link #table} / {@link #tableOf}: {@code TABLE} is the default
     * wide-table form; {@code COMPACT} renders as a vertical list (one record per block,
     * one field per line). Telegram binds COMPACT before each command since chat clients
     * wrap any line wider than ~40 chars; SSH leaves the default alone for full tables.
     */
    public enum Layout { TABLE, COMPACT }

    private static final ThreadLocal<Layout> LAYOUT = ThreadLocal.withInitial(() -> Layout.TABLE);

    public static void bindLayout(Layout l) { LAYOUT.set(l); }
    public static void clearLayout() { LAYOUT.remove(); }
    public static Layout currentLayout() { return LAYOUT.get(); }

    /** Shared, pretty-printing JSON mapper — also reused by commands that need to deserialize JSON request bodies. */
    public static final ObjectMapper JSON_MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(SerializationFeature.INDENT_OUTPUT);
    public static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private Out() {}

    public static String json(Object o) {
        try { return JSON_MAPPER.writeValueAsString(o); }
        catch (Exception e) { return "json error: " + e.getMessage(); }
    }

    public static String yaml(Object o) {
        try { return YAML_MAPPER.writeValueAsString(o); }
        catch (Exception e) { return "yaml error: " + e.getMessage(); }
    }

    /** Two-column key/value rendering — fine for a single object detail view. */
    public static String kv(List<String[]> rows) {
        int kw = rows.stream().mapToInt(r -> visibleLength(r[0])).max().orElse(0);
        StringBuilder sb = new StringBuilder();
        for (String[] r : rows) sb.append(pad(r[0], kw)).append("  ").append(r[1] == null ? "" : r[1]).append('\n');
        return sb.toString();
    }

    /** Generic table: header row + value rows. {@code rows} cells must already be strings. */
    public static String table(List<String> header, List<List<String>> rows) {
        int cols = header.size();
        int[] w = new int[cols];
        for (int i = 0; i < cols; i++) w[i] = visibleLength(header.get(i));
        for (List<String> r : rows) for (int i = 0; i < cols; i++) {
            String s = i < r.size() && r.get(i) != null ? r.get(i) : "";
            int len = visibleLength(s);
            if (len > w[i]) w[i] = len;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols; i++) sb.append(Ansi.bold(pad(header.get(i), w[i]))).append(i == cols - 1 ? "" : "  ");
        sb.append('\n');
        for (int i = 0; i < cols; i++) sb.append(Ansi.dim("-".repeat(w[i]))).append(i == cols - 1 ? "" : "  ");
        sb.append('\n');
        for (List<String> r : rows) {
            for (int i = 0; i < cols; i++) {
                String s = i < r.size() && r.get(i) != null ? r.get(i) : "";
                sb.append(pad(s, w[i])).append(i == cols - 1 ? "" : "  ");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static final java.util.regex.Pattern ANSI_RE = java.util.regex.Pattern.compile("\\[[;\\d]*[ -/]*[@-~]");

    /** Length of a string ignoring ANSI escape sequences — needed for correct column padding. */
    public static int visibleLength(String s) {
        if (s == null) return 0;
        return ANSI_RE.matcher(s).replaceAll("").length();
    }

    /** Convenience: project a list of POJOs into a table by named extractors. */
    public static <T> String tableOf(List<T> items, List<String> header, List<Function<T, ?>> getters) {
        List<List<String>> rows = new ArrayList<>(items.size());
        for (T it : items) {
            List<String> row = new ArrayList<>(getters.size());
            for (Function<T, ?> g : getters) {
                Object v = it == null ? null : g.apply(it);
                row.add(v == null ? "" : String.valueOf(v));
            }
            rows.add(row);
        }
        if (currentLayout() == Layout.COMPACT) return compact(header, rows);
        return table(header, rows);
    }

    /**
     * Vertical, chat-friendly rendering: one block per record, one field per line.
     * Headers become the labels; the first column doubles as the record's lead line so
     * a quick scan still picks out ids. Designed for &lt; 40-char-wide displays (phones).
     */
    public static String compact(List<String> header, List<List<String>> rows) {
        if (rows.isEmpty()) return "";
        int labelWidth = header.stream().mapToInt(String::length).max().orElse(0);
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String lead = !row.isEmpty() && row.get(0) != null ? row.get(0) : "(record " + (r + 1) + ")";
            sb.append(lead).append('\n');
            for (int i = 1; i < header.size(); i++) {
                String value = i < row.size() && row.get(i) != null ? row.get(i) : "";
                if (value.isEmpty() || "·".equals(value) || "-".equals(value)) continue;  // skip empties
                sb.append("  ").append(pad(header.get(i).toLowerCase(), labelWidth)).append("  ")
                    .append(value).append('\n');
            }
            if (r < rows.size() - 1) sb.append('\n');  // blank line between records
        }
        return sb.toString();
    }

    public static String pad(String s, int w) {
        if (s == null) s = "";
        int len = visibleLength(s);
        if (len >= w) return s;
        return s + " ".repeat(w - len);
    }

    /** Render any value in the requested format. {@code defaultRender} runs when format=table. */
    public static String render(Format fmt, Object value, java.util.function.Supplier<String> defaultRender) {
        return switch (fmt) {
            case JSON -> json(value);
            case YAML -> yaml(value);
            case TABLE -> defaultRender.get();
        };
    }

    public static void println(PrintWriter w, String s) {
        w.println(s);
        w.flush();
    }
}
