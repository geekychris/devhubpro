package io.devportal.runtime.k8s;

import io.devportal.manifest.Manifest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-asset proxy config — wraps {@link ProxyRoutesService} edits to the workspace
 * {@code devportal.yaml}. Changes are left uncommitted; the user reviews and pushes
 * them via the existing Changes tab.
 */
@RestController
@RequestMapping("/api/assets/{id}/proxy")
public class AssetProxyController {

    private final ProxyRoutesService routes;

    public AssetProxyController(ProxyRoutesService routes) {
        this.routes = routes;
    }

    public record ProxyRequest(String path, String portSlot, Boolean stripPrefix, String host) {}

    @GetMapping
    public Map<String, Object> get(@PathVariable String id) {
        Manifest.Proxy p = routes.currentProxy(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assetId", id);
        body.put("proxy", p == null ? null : proxyToMap(p));
        return body;
    }

    private static Map<String, Object> proxyToMap(Manifest.Proxy p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", p.path());
        m.put("portSlot", p.portSlot());
        m.put("stripPrefix", p.stripPrefix() == null ? true : p.stripPrefix());
        m.put("host", p.host());
        return m;
    }

    @PostMapping
    public Map<String, Object> create(@PathVariable String id, @RequestBody ProxyRequest req)
            throws IOException {
        return write(id, req);
    }

    @PutMapping
    public Map<String, Object> update(@PathVariable String id, @RequestBody ProxyRequest req)
            throws IOException {
        return write(id, req);
    }

    @DeleteMapping
    public Map<String, Object> remove(@PathVariable String id) throws IOException {
        routes.removeProxy(id);
        return Map.of("assetId", id, "removed", true);
    }

    private Map<String, Object> write(String id, ProxyRequest req) throws IOException {
        Manifest.Proxy written = routes.setProxy(id, req.path(), req.portSlot(),
            req.stripPrefix(), req.host());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assetId", id);
        body.put("proxy", proxyToMap(written));
        return body;
    }
}
