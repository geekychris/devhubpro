package io.devportal.meta;

import io.devportal.meta.dto.AddConsumesRequest;
import io.devportal.meta.dto.CreateMetaAssetRequest;
import io.devportal.meta.dto.MetaAssetView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MetaAssetController {

    private final MetaAssetService service;

    public MetaAssetController(MetaAssetService service) { this.service = service; }

    @GetMapping("/api/meta-assets")
    public List<MetaAssetView> list() { return service.list(); }

    @GetMapping("/api/meta-assets/{id}")
    public MetaAssetView get(@PathVariable String id) { return service.get(id); }

    @PostMapping("/api/meta-assets")
    public ResponseEntity<MetaAssetView> create(@Valid @RequestBody CreateMetaAssetRequest req) {
        MetaAssetView created = service.create(req);
        return ResponseEntity.created(java.net.URI.create("/api/meta-assets/" + created.id())).body(created);
    }

    @PutMapping("/api/meta-assets/{id}")
    public MetaAssetView update(@PathVariable String id, @Valid @RequestBody CreateMetaAssetRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/api/meta-assets/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/assets/{id}/consumes")
    public List<Consumes> consumesFor(@PathVariable String id) { return service.consumesFor(id); }

    @PostMapping("/api/assets/{id}/consumes")
    public ResponseEntity<Consumes> attach(@PathVariable String id, @Valid @RequestBody AddConsumesRequest body) {
        return ResponseEntity.status(201).body(service.attach(id, body));
    }

    @DeleteMapping("/api/assets/{id}/consumes/{metaAssetId}")
    public ResponseEntity<Void> detach(
        @PathVariable String id,
        @PathVariable String metaAssetId,
        @RequestParam(required = false) String role
    ) {
        service.detach(id, metaAssetId, role);
        return ResponseEntity.noContent().build();
    }
}
