package io.devportal.build;

import io.devportal.build.dto.BuildView;
import io.devportal.build.dto.KickBuildRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BuildController {

    private final BuildService service;

    public BuildController(BuildService service) {
        this.service = service;
    }

    @PostMapping("/api/assets/{id}/builds")
    public ResponseEntity<BuildView> kick(
        @PathVariable String id,
        @Valid @RequestBody(required = false) KickBuildRequest body
    ) throws IOException, GitAPIException {
        KickBuildRequest req = body == null ? new KickBuildRequest(null, null, null, null) : body;
        BuildView created = service.kick(id, req);
        return ResponseEntity.status(202).body(created);
    }

    @GetMapping("/api/assets/{id}/builds")
    public List<BuildView> list(
        @PathVariable String id,
        @RequestParam(required = false, defaultValue = "50") int limit
    ) {
        return service.listFor(id, limit);
    }

    @GetMapping("/api/builds/{id}")
    public BuildView get(@PathVariable long id) {
        return service.get(id);
    }

    @GetMapping(value = "/api/builds/{id}/log", produces = MediaType.TEXT_PLAIN_VALUE)
    public String log(@PathVariable long id) throws IOException {
        return service.readLog(id);
    }

    /** All builds in the chain containing build {id}, in chronological order. */
    @GetMapping("/api/builds/{id}/chain")
    public List<BuildView> chain(@PathVariable long id) {
        return service.chain(id);
    }

    /**
     * Rich progress over a chain (parent + children + parsed labels + tail logs + error hints) in
     * one round-trip. Designed for live UI polling and CLI / MCP diagnosis.
     */
    @GetMapping("/api/builds/{id}/progress")
    public io.devportal.build.dto.BuildProgress progress(@PathVariable long id) {
        return service.progress(id);
    }

    /** Most recent builds across all assets. */
    @GetMapping("/api/builds")
    public List<BuildView> recent(@RequestParam(required = false, defaultValue = "50") int limit) {
        return service.recent(limit);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/api/builds/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
