package io.devportal.cli.commands;

import io.devportal.asset.TagCatalogController;
import io.devportal.cli.output.Out;
import java.util.List;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Component
@Command(name = "tag", description = "Tag catalog (most-recently-used across all assets).")
public class TagCommands {

    private final TagCatalogController catalog;

    public TagCommands(TagCatalogController catalog) { this.catalog = catalog; }

    @Command(name = "list", description = "List tags, optionally prefix-filtered.")
    public Integer list(@Option(names = "-q", description = "Prefix filter.") String q,
                       @Option(names = "--json") boolean json) {
        List<TagCatalogController.TagSummary> tags = catalog.list(q);
        System.out.print(json ? Out.json(tags) + "\n" : Out.tableOf(tags,
            List.of("TAG", "COUNT", "LAST_USED"),
            List.<java.util.function.Function<TagCatalogController.TagSummary, ?>>of(
                TagCatalogController.TagSummary::tag,
                TagCatalogController.TagSummary::usageCount,
                t -> t.lastUsedAt() == null ? "" : t.lastUsedAt().toString())));
        return 0;
    }
}
