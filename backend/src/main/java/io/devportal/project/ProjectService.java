package io.devportal.project;

import com.fasterxml.jackson.databind.JsonNode;
import io.devportal.asset.Asset;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.NotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ProjectService {

    private final ProjectRepository projects;
    private final AssetRepository assets;

    public ProjectService(ProjectRepository projects, AssetRepository assets) {
        this.projects = projects;
        this.assets = assets;
    }

    /** Flat list of all projects (parent-first order) — frontend builds the tree from parentId. */
    public List<Project> list() {
        return projects.findAll();
    }

    public Project get(long id) {
        return projects.findById(id).orElseThrow(() -> new NotFoundException("Project " + id + " not found"));
    }

    public List<Asset> assetsOf(long id) {
        get(id); // 404 check
        return projects.findAssets(id);
    }

    public Project create(Long parentId, String name, String description, JsonNode metadata) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (parentId != null) get(parentId); // ensure parent exists
        return projects.insert(parentId, name.trim(), description, metadata, 0);
    }

    public Project update(long id, String name, String description, JsonNode metadata, Integer sortOrder) {
        get(id); // 404 check
        return projects.update(id, null, name, description, metadata, sortOrder);
    }

    /** Move the project to a different parent (or to root with parentId=null). Refuses cycles. */
    public Project setParent(long id, Long newParentId) {
        Project self = get(id);
        if (newParentId != null && newParentId.equals(id)) {
            throw new IllegalArgumentException("a project cannot be its own parent");
        }
        if (newParentId != null) {
            // Walk up from newParent — if we hit `id`, we'd create a cycle.
            Long cur = newParentId;
            Set<Long> seen = new HashSet<>();
            while (cur != null) {
                if (cur.equals(id)) {
                    throw new IllegalArgumentException("would create a cycle (descendant cannot be parent)");
                }
                if (!seen.add(cur)) break; // defensive — should never happen
                Project p = projects.findById(cur).orElse(null);
                if (p == null) break;
                cur = p.parentId();
            }
        }
        projects.setParent(id, newParentId);
        return projects.findById(id).orElse(self);
    }

    public void delete(long id) {
        get(id); // 404 check
        projects.delete(id); // cascades to children + project_asset rows
    }

    public boolean addAsset(long projectId, String assetId) {
        get(projectId);
        if (!assets.existsById(assetId)) {
            throw new NotFoundException("Asset '" + assetId + "' not found");
        }
        return projects.addAsset(projectId, assetId);
    }

    public boolean removeAsset(long projectId, String assetId) {
        get(projectId);
        return projects.removeAsset(projectId, assetId);
    }

    /**
     * Move an asset between projects atomically (well, two ops back-to-back). When
     * {@code copy=true}, just adds to the destination without removing from the source.
     */
    public void moveAsset(long fromProjectId, long toProjectId, String assetId, boolean copy) {
        addAsset(toProjectId, assetId);
        if (!copy && fromProjectId != toProjectId) {
            projects.removeAsset(fromProjectId, assetId);
        }
    }

    /**
     * For a list of asset ids, returns each one's set of project memberships. Used by the
     * assets page to render "in: foo, bar" chips.
     */
    public Map<String, List<Long>> memberships(List<String> assetIds) {
        Map<String, List<Long>> out = new HashMap<>();
        for (var row : projects.memberRows(assetIds)) {
            out.computeIfAbsent(row.assetId(), k -> new ArrayList<>()).add(row.projectId());
        }
        return out;
    }
}
