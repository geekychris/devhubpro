package io.devportal.panels;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Server-described UI panel. Frontend renders generically based on {@code layout}.
 * Future plugins return additional panels via the same shape.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Panel(
    String id,
    String title,
    String layout,           // "kv" | "list" | "code" | "links"
    List<KvItem> items,
    List<String> list,
    String code,
    List<Link> links
) {
    public record KvItem(String key, String value) {}
    public record Link(String label, String url) {}

    public static Panel kv(String id, String title, List<KvItem> items) {
        return new Panel(id, title, "kv", items, null, null, null);
    }
    public static Panel list(String id, String title, List<String> items) {
        return new Panel(id, title, "list", null, items, null, null);
    }
    public static Panel code(String id, String title, String code) {
        return new Panel(id, title, "code", null, null, code, null);
    }
    public static Panel links(String id, String title, List<Link> links) {
        return new Panel(id, title, "links", null, null, null, links);
    }
}
