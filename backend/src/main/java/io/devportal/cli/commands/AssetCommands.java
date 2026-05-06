package io.devportal.cli.commands;

import io.devportal.asset.AssetService;
import io.devportal.asset.dto.AddDependencyRequest;
import io.devportal.asset.dto.AssetView;
import io.devportal.asset.dto.CreateAssetRequest;
import io.devportal.asset.dto.DependencyView;
import io.devportal.asset.dto.RegisterFromGitHubRequest;
import io.devportal.asset.dto.UpdateAssetRequest;
import io.devportal.cli.output.Ansi;
import io.devportal.cli.output.Out;
import java.util.List;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "asset", description = "Asset CRUD, dependency edges, and graph queries.")
public class AssetCommands {

    private final AssetService svc;

    public AssetCommands(AssetService svc) { this.svc = svc; }

    @Command(name = "list", description = "List assets, optionally filtered.")
    public Integer list(
        @Option(names = {"-q", "--query"}, description = "Multi-term search across id/name/desc/tags.") String q,
        @Option(names = "--type", description = "Filter by asset type (library, service, ...).") String type,
        @Option(names = "--lifecycle", description = "Filter by lifecycle (experimental, stable, ...).") String lifecycle,
        @Option(names = "--favorites", description = "Only favorites.") Boolean favorite,
        @Option(names = "--json") boolean json
    ) {
        List<AssetView> items = svc.list(q, type, lifecycle, favorite);
        System.out.print(json ? Out.json(items) + "\n" : Out.tableOf(items,
            List.of("ID", "TYPE", "LANG", "LIFECYCLE", "★", "♥", "PIN", "NAME"),
            List.of(AssetView::id, AssetView::type, AssetView::language,
                a -> Ansi.status(a.lifecycle()),
                a -> a.rating() == null ? Ansi.gray("-") : Ansi.yellow(a.rating().toString()),
                a -> a.favorite() ? Ansi.red("♥") : Ansi.gray("·"),
                a -> a.dashboardPinned() ? Ansi.cyan("📌") : Ansi.gray("·"),
                AssetView::name)));
        return 0;
    }

    @Command(name = "get", description = "Show one asset.")
    public Integer get(@Parameters(paramLabel = "ID") String id, @Option(names = "--json") boolean json) {
        AssetView a = svc.get(id);
        System.out.println(json ? Out.json(a) : Out.yaml(a));
        return 0;
    }

    @Command(name = "create", description = "Create an asset record (without GitHub registration).")
    public Integer create(
        @Option(names = "--id", required = true) String id,
        @Option(names = "--name", required = true) String name,
        @Option(names = "--type", defaultValue = "library") String type,
        @Option(names = "--repo-url") String repoUrl,
        @Option(names = "--owner") String owner,
        @Option(names = "--language") String language,
        @Option(names = "--description") String description,
        @Option(names = "--branch", description = "Default branch (default: main).") String branch,
        @Option(names = "--tag", description = "Tag (repeatable).") List<String> tags,
        @Option(names = "--lifecycle", defaultValue = "experimental") String lifecycle
    ) {
        CreateAssetRequest req = new CreateAssetRequest(
            id, name, description, owner, type, language, repoUrl, branch, tags, lifecycle);
        AssetView a = svc.create(req);
        System.out.println(Out.yaml(a));
        return 0;
    }

    @Command(name = "register", description = "Register an asset from a GitHub repo (owner/repo). "
        + "Picks up devportal.yaml at HEAD if present.")
    public Integer register(
        @Parameters(paramLabel = "FULLNAME", description = "owner/repo") String fullName,
        @Option(names = "--id", description = "Override the auto-derived id.") String overrideId
    ) throws Exception {
        AssetView a = svc.registerFromGitHub(new RegisterFromGitHubRequest(fullName, overrideId));
        System.out.println(Out.yaml(a));
        return 0;
    }

    @Command(name = "update", description = "Update mutable fields on an asset.")
    public Integer update(
        @Parameters(paramLabel = "ID") String id,
        @Option(names = "--name") String name,
        @Option(names = "--description") String description,
        @Option(names = "--owner") String owner,
        @Option(names = "--type") String type,
        @Option(names = "--language") String language,
        @Option(names = "--repo-url") String repoUrl,
        @Option(names = "--branch") String branch,
        @Option(names = "--lifecycle") String lifecycle,
        @Option(names = "--namespace", description = "k8s namespace.") String k8sNamespace,
        @Option(names = "--favorite") Boolean favorite,
        @Option(names = "--rating", description = "0 clears, 1-5 sets.") Integer rating,
        @Option(names = "--pin", description = "Pin/unpin on dashboard.") Boolean dashboardPinned,
        @Option(names = "--tag", description = "Replace tag set with these (repeatable).") List<String> tags
    ) {
        UpdateAssetRequest req = new UpdateAssetRequest(name, description, owner, type, language,
            repoUrl, branch, tags, lifecycle, k8sNamespace, favorite, rating, dashboardPinned);
        AssetView a = svc.update(id, req);
        System.out.println(Out.yaml(a));
        return 0;
    }

    @Command(name = "delete", description = "Delete an asset record (workspace stays on disk).")
    public Integer delete(@Parameters(paramLabel = "ID") String id) {
        svc.delete(id);
        System.out.println("deleted " + id);
        return 0;
    }

    @Command(name = "deps", description = "Producers (what this asset depends on).")
    public Integer deps(@Parameters(paramLabel = "ID") String id, @Option(names = "--json") boolean json) {
        List<DependencyView> deps = svc.dependenciesOf(id);
        System.out.print(json ? Out.json(deps) + "\n" : Out.tableOf(deps,
            List.of("PRODUCER", "KIND", "VERSION"),
            List.of(DependencyView::producerId, DependencyView::kind, DependencyView::versionRef)));
        return 0;
    }

    @Command(name = "consumers", description = "Consumers (what depends on this asset).")
    public Integer consumers(@Parameters(paramLabel = "ID") String id, @Option(names = "--json") boolean json) {
        List<DependencyView> cs = svc.consumersOf(id);
        System.out.print(json ? Out.json(cs) + "\n" : Out.tableOf(cs,
            List.of("CONSUMER", "KIND", "VERSION"),
            List.of(DependencyView::consumerId, DependencyView::kind, DependencyView::versionRef)));
        return 0;
    }

    @Command(name = "add-dep", description = "Create a dependency edge consumer -> producer.")
    public Integer addDep(
        @Parameters(paramLabel = "CONSUMER") String consumer,
        @Parameters(paramLabel = "PRODUCER") String producer,
        @Option(names = "--kind", defaultValue = "build") String kind,
        @Option(names = "--version") String versionRef
    ) {
        DependencyView dep = svc.addDependency(consumer, new AddDependencyRequest(producer, versionRef, kind));
        System.out.println(Out.yaml(dep));
        return 0;
    }

    @Command(name = "rm-dep", description = "Remove a dependency edge.")
    public Integer rmDep(
        @Parameters(paramLabel = "CONSUMER") String consumer,
        @Parameters(paramLabel = "PRODUCER") String producer,
        @Option(names = "--kind", defaultValue = "build") String kind
    ) {
        svc.removeDependency(consumer, producer, kind);
        System.out.println("removed " + consumer + " -> " + producer + " (" + kind + ")");
        return 0;
    }
}
