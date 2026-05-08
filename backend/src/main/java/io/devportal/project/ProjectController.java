package io.devportal.project;

import com.fasterxml.jackson.databind.JsonNode;
import io.devportal.asset.Asset;
import java.util.List;
import java.util.Map;
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
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    @GetMapping
    public List<Project> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public Project get(@PathVariable long id) {
        return service.get(id);
    }

    @GetMapping("/{id}/assets")
    public List<Asset> assets(@PathVariable long id) {
        return service.assetsOf(id);
    }

    public record CreateRequest(Long parentId, String name, String description, JsonNode metadata) {}

    @PostMapping
    public Project create(@RequestBody CreateRequest req) {
        return service.create(req.parentId(), req.name(), req.description(), req.metadata());
    }

    public record UpdateRequest(String name, String description, JsonNode metadata, Integer sortOrder) {}

    @PatchMapping("/{id}")
    public Project update(@PathVariable long id, @RequestBody UpdateRequest req) {
        return service.update(id, req.name(), req.description(), req.metadata(), req.sortOrder());
    }

    public record SetParentRequest(Long parentId) {}

    /** Move a project under a new parent (parentId=null moves it to the root). */
    @PostMapping("/{id}/parent")
    public Project setParent(@PathVariable long id, @RequestBody SetParentRequest req) {
        return service.setParent(id, req.parentId());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/assets/{assetId}")
    public Map<String, Object> addAsset(@PathVariable long id, @PathVariable String assetId) {
        boolean added = service.addAsset(id, assetId);
        return Map.of("projectId", id, "assetId", assetId, "added", added);
    }

    @DeleteMapping("/{id}/assets/{assetId}")
    public Map<String, Object> removeAsset(@PathVariable long id, @PathVariable String assetId) {
        boolean removed = service.removeAsset(id, assetId);
        return Map.of("projectId", id, "assetId", assetId, "removed", removed);
    }

    /**
     * Drag-drop endpoint: move (or copy with {@code ?copy=true}) an asset from
     * {@code fromProjectId} to this project.
     */
    @PostMapping("/{id}/assets/{assetId}/move-from/{fromProjectId}")
    public Map<String, Object> moveAsset(@PathVariable long id,
                                         @PathVariable String assetId,
                                         @PathVariable long fromProjectId,
                                         @RequestParam(name = "copy", defaultValue = "false") boolean copy) {
        service.moveAsset(fromProjectId, id, assetId, copy);
        return Map.of("from", fromProjectId, "to", id, "assetId", assetId, "mode", copy ? "copy" : "move");
    }

    public record MembershipsRequest(List<String> assetIds) {}

    /** Bulk lookup: project ids each asset is a member of. Used by the assets page. */
    @PostMapping("/memberships")
    public Map<String, List<Long>> memberships(@RequestBody MembershipsRequest req) {
        return service.memberships(req.assetIds());
    }
}
