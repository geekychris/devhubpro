package io.devportal.cli.commands;

import io.devportal.cli.output.Out;
import io.devportal.search.SearchService;
import io.devportal.search.dto.SearchResult;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "search", description = "Global search across asset metadata + workspace .md files.")
public class SearchCommands {

    private final SearchService svc;

    public SearchCommands(SearchService svc) { this.svc = svc; }

    @Command(name = "run", description = "Search assets and (optionally) docs.")
    public Integer run(
        @Parameters(paramLabel = "QUERY") String q,
        @Option(names = "--no-docs", description = "Skip the doc full-text scan.") boolean noDocs,
        @Option(names = "--json") boolean json
    ) {
        SearchResult r = svc.search(q, !noDocs);
        System.out.println(json ? Out.json(r) : Out.yaml(r));
        return 0;
    }
}
