package io.devportal.cli.commands;

import io.devportal.bulk.BulkImportService;
import io.devportal.bulk.dto.BulkImportRequest;
import io.devportal.bulk.dto.ImportJob;
import io.devportal.cli.output.Out;
import io.devportal.github.GitHubClient;
import io.devportal.github.GitHubRepoSummary;
import java.util.List;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "import", description = "Bulk import GitHub orgs.")
public class BulkImportCommands {

    private final BulkImportService bulk;
    private final GitHubClient github;

    public BulkImportCommands(BulkImportService bulk, GitHubClient github) {
        this.bulk = bulk;
        this.github = github;
    }

    @Command(name = "preview", description = "List repos in an org without registering them.")
    public Integer preview(@Parameters(paramLabel = "OWNER") String owner, @Option(names = "--json") boolean json) throws Exception {
        List<GitHubRepoSummary> repos = github.listOrgRepos(owner);
        System.out.println(json ? Out.json(repos) : Out.yaml(repos));
        return 0;
    }

    @Command(name = "start", description = "Start a bulk import job for an org.")
    public Integer start(
        @Parameters(paramLabel = "OWNER") String owner,
        @Option(names = "--lang", description = "Language filter (repeatable).") List<String> languages,
        @Option(names = "--include", description = "Include regex (repeatable).") List<String> includes,
        @Option(names = "--exclude", description = "Exclude regex (repeatable).") List<String> excludes,
        @Option(names = "--skip-archived", defaultValue = "true") Boolean skipArchived,
        @Option(names = "--skip-forks", defaultValue = "true") Boolean skipForks
    ) {
        BulkImportRequest req = new BulkImportRequest(
            languages, includes, excludes, skipArchived, skipForks, true, true);
        ImportJob job = bulk.start(owner, req);
        System.out.println("started job " + job.id());
        return 0;
    }

    @Command(name = "list", description = "All known import jobs.")
    public Integer list(@Option(names = "--json") boolean json) {
        List<ImportJob> jobs = bulk.list();
        System.out.println(json ? Out.json(jobs) : Out.yaml(jobs));
        return 0;
    }

    @Command(name = "get", description = "One import job by id.")
    public Integer get(@Parameters(paramLabel = "ID") long id) {
        ImportJob j = bulk.get(id);
        if (j == null) {
            System.out.println("not found");
            return 1;
        }
        System.out.println(Out.yaml(j));
        return 0;
    }
}
