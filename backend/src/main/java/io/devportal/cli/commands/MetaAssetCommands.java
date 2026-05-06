package io.devportal.cli.commands;

import io.devportal.cli.output.Out;
import io.devportal.meta.Consumes;
import io.devportal.meta.MetaAssetService;
import io.devportal.meta.dto.AddConsumesRequest;
import io.devportal.meta.dto.CreateMetaAssetRequest;
import io.devportal.meta.dto.MetaAssetView;
import java.util.List;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "meta", description = "Shared infrastructure (postgres, redis, ...) and consumes edges.")
public class MetaAssetCommands {

    private final MetaAssetService svc;

    public MetaAssetCommands(MetaAssetService svc) { this.svc = svc; }

    @Command(name = "list", description = "All meta-assets.")
    public Integer list(@Option(names = "--json") boolean json) {
        List<MetaAssetView> items = svc.list();
        System.out.println(json ? Out.json(items) : Out.yaml(items));
        return 0;
    }

    @Command(name = "get", description = "One meta-asset.")
    public Integer get(@Parameters(paramLabel = "ID") String id) {
        MetaAssetView m = svc.get(id);
        System.out.println(Out.yaml(m));
        return 0;
    }

    @Command(name = "create", description = "Create a meta-asset.")
    public Integer create(@Parameters(paramLabel = "BODY_JSON",
        description = "JSON CreateMetaAssetRequest, e.g. '{\"id\":\"redis\",\"kind\":\"cache\"}'") String json) throws Exception {
        CreateMetaAssetRequest req = Out.JSON_MAPPER.readValue(json, CreateMetaAssetRequest.class);
        MetaAssetView m = svc.create(req);
        System.out.println(Out.yaml(m));
        return 0;
    }

    @Command(name = "update", description = "Update a meta-asset.")
    public Integer update(@Parameters(paramLabel = "ID") String id,
                         @Parameters(paramLabel = "BODY_JSON") String json) throws Exception {
        CreateMetaAssetRequest req = Out.JSON_MAPPER.readValue(json, CreateMetaAssetRequest.class);
        MetaAssetView m = svc.update(id, req);
        System.out.println(Out.yaml(m));
        return 0;
    }

    @Command(name = "delete", description = "Delete a meta-asset.")
    public Integer delete(@Parameters(paramLabel = "ID") String id) {
        svc.delete(id);
        System.out.println("deleted " + id);
        return 0;
    }

    @Command(name = "consumes", description = "Consumes edges for an asset.")
    public Integer consumesFor(@Parameters(paramLabel = "ID") String id) {
        List<Consumes> edges = svc.consumesFor(id);
        System.out.println(Out.yaml(edges));
        return 0;
    }

    @Command(name = "attach", description = "Attach an asset to a meta-asset with a role.")
    public Integer attach(
        @Parameters(paramLabel = "ASSET") String assetId,
        @Parameters(paramLabel = "META") String metaAssetId,
        @Option(names = "--role") String role
    ) {
        Consumes c = svc.attach(assetId, new AddConsumesRequest(metaAssetId, role));
        System.out.println(Out.yaml(c));
        return 0;
    }

    @Command(name = "detach", description = "Detach an asset from a meta-asset.")
    public Integer detach(
        @Parameters(paramLabel = "ASSET") String assetId,
        @Parameters(paramLabel = "META") String metaAssetId,
        @Option(names = "--role") String role
    ) {
        svc.detach(assetId, metaAssetId, role);
        System.out.println("detached");
        return 0;
    }
}
