package io.devportal.asset;

import io.devportal.asset.dto.AddDependencyRequest;
import io.devportal.asset.dto.AssetGraphView;
import io.devportal.asset.dto.AssetView;
import io.devportal.asset.dto.CreateAssetRequest;
import io.devportal.asset.dto.DependencyView;
import io.devportal.asset.dto.RegisterFromGitHubRequest;
import io.devportal.asset.dto.UpdateAssetRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private final AssetService service;

    public AssetController(AssetService service) {
        this.service = service;
    }

    @GetMapping
    public List<AssetView> list(
        @RequestParam(name = "q", required = false) String query,
        @RequestParam(required = false) String type,
        @RequestParam(required = false) String lifecycle,
        @RequestParam(required = false) Boolean favorite
    ) {
        return service.list(query, type, lifecycle, favorite);
    }

    @PostMapping
    public ResponseEntity<AssetView> create(@Valid @RequestBody CreateAssetRequest req) {
        AssetView created = service.create(req);
        return ResponseEntity.created(java.net.URI.create("/api/assets/" + created.id())).body(created);
    }

    @PostMapping("/from-github")
    public ResponseEntity<AssetView> registerFromGitHub(@Valid @RequestBody RegisterFromGitHubRequest req) throws IOException {
        AssetView created = service.registerFromGitHub(req);
        return ResponseEntity.created(java.net.URI.create("/api/assets/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public AssetView get(@PathVariable String id) {
        return service.get(id);
    }

    @PatchMapping("/{id}")
    public AssetView update(@PathVariable String id, @RequestBody UpdateAssetRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/dependencies")
    public List<DependencyView> dependenciesOf(@PathVariable String id) {
        return service.dependenciesOf(id);
    }

    @GetMapping("/{id}/consumers")
    public List<DependencyView> consumersOf(@PathVariable String id) {
        return service.consumersOf(id);
    }

    @PostMapping("/{id}/dependencies")
    public ResponseEntity<DependencyView> addDependency(
        @PathVariable String id, @Valid @RequestBody AddDependencyRequest req
    ) {
        return ResponseEntity.status(201).body(service.addDependency(id, req));
    }

    @DeleteMapping("/{id}/dependencies/{producerId}")
    public ResponseEntity<Void> removeDependency(
        @PathVariable String id,
        @PathVariable String producerId,
        @RequestParam(required = false, defaultValue = "build") String kind
    ) {
        service.removeDependency(id, producerId, kind);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/graph")
    public AssetGraphView graph(
        @PathVariable String id,
        @RequestParam(required = false, defaultValue = "both") String direction,
        @RequestParam(required = false, defaultValue = "-1") int producerDepth,
        @RequestParam(required = false, defaultValue = "1") int consumerDepth
    ) {
        return service.graph(id, direction, producerDepth, consumerDepth);
    }
}
