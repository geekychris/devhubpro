package io.devportal.meta;

import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.ConflictException;
import io.devportal.asset.error.NotFoundException;
import io.devportal.meta.dto.AddConsumesRequest;
import io.devportal.meta.dto.CreateMetaAssetRequest;
import io.devportal.meta.dto.MetaAssetView;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MetaAssetService {

    private final MetaAssetRepository repo;
    private final AssetRepository assets;

    public MetaAssetService(MetaAssetRepository repo, AssetRepository assets) {
        this.repo = repo;
        this.assets = assets;
    }

    public List<MetaAssetView> list() {
        return repo.findAll().stream().map(MetaAssetView::of).toList();
    }

    public MetaAssetView get(String id) {
        return MetaAssetView.of(load(id));
    }

    @Transactional
    public MetaAssetView create(CreateMetaAssetRequest r) {
        if (repo.existsById(r.id())) {
            throw new ConflictException("Meta-asset '" + r.id() + "' already exists");
        }
        MetaAsset m = new MetaAsset(
            r.id(), r.name(), r.kind(),
            r.config() == null ? Map.of() : r.config(),
            r.provisionedByPortal() != null && r.provisionedByPortal(),
            null, null
        );
        repo.insert(m);
        return MetaAssetView.of(repo.findById(r.id()).orElseThrow());
    }

    @Transactional
    public MetaAssetView update(String id, CreateMetaAssetRequest r) {
        load(id);
        MetaAsset m = new MetaAsset(
            id, r.name(), r.kind(),
            r.config() == null ? Map.of() : r.config(),
            r.provisionedByPortal() != null && r.provisionedByPortal(),
            null, null
        );
        repo.update(m);
        return MetaAssetView.of(repo.findById(id).orElseThrow());
    }

    @Transactional
    public void delete(String id) {
        if (repo.delete(id) == 0) {
            throw new NotFoundException("Meta-asset '" + id + "' not found");
        }
    }

    public List<Consumes> consumesFor(String assetId) {
        if (!assets.existsById(assetId)) {
            throw new NotFoundException("Asset '" + assetId + "' not found");
        }
        return repo.consumesFor(assetId);
    }

    @Transactional
    public Consumes attach(String assetId, AddConsumesRequest r) {
        if (!assets.existsById(assetId)) {
            throw new NotFoundException("Asset '" + assetId + "' not found");
        }
        if (!repo.existsById(r.metaAssetId())) {
            throw new NotFoundException("Meta-asset '" + r.metaAssetId() + "' not found");
        }
        return repo.addConsumes(assetId, r.metaAssetId(), r.role());
    }

    @Transactional
    public void detach(String assetId, String metaAssetId, String role) {
        if (repo.removeConsumes(assetId, metaAssetId, role) == 0) {
            throw new NotFoundException("Consumes edge not found");
        }
    }

    private MetaAsset load(String id) {
        return repo.findById(id).orElseThrow(
            () -> new NotFoundException("Meta-asset '" + id + "' not found"));
    }
}
