package io.devportal.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.devportal.analyze.AnalyzeService;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.AssetService;
import io.devportal.bulk.dto.BulkImportRequest;
import io.devportal.bulk.dto.ImportJob;
import io.devportal.discover.ResourceDetector;
import io.devportal.github.GitHubClient;
import io.devportal.github.GitHubRepoSummary;
import io.devportal.workspace.WorkspaceService;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BulkImportServiceTest {

    private final GitHubClient github = mock(GitHubClient.class);
    private final AssetRepository assets = mock(AssetRepository.class);

    private BulkImportService service(java.util.concurrent.Executor ex) {
        return new BulkImportService(github, assets, mock(AssetService.class), mock(AnalyzeService.class),
            mock(ResourceDetector.class), mock(WorkspaceService.class), ex);
    }

    private static GitHubRepoSummary repo(String name, String lang, boolean fork) {
        return new GitHubRepoSummary("o/" + name, name, "o", null, "main", "https://github.com/o/" + name,
            "https://github.com/o/" + name + ".git", fork, false, false, List.of(), null, lang);
    }

    private static BulkImportRequest javaOnly() {
        // registers only (no clone/analyze/auto-wire), so the mocks stay simple
        return new BulkImportRequest(List.of("Java"), List.of(), List.of(), true, true, false, false);
    }

    private static ImportJob await(BulkImportService s, long id) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            ImportJob j = s.get(id);
            if (!j.status().equals("queued") && !j.status().equals("running")) return j;
            Thread.sleep(20);
        }
        throw new AssertionError("job did not finish: " + s.get(id));
    }

    @Test
    void filtersAndChecksLanguagesOnlyWhereThePrimaryMisses() throws Exception {
        when(github.listOrgRepos(anyString(), any())).thenReturn(List.of(
            repo("java-app", "Java", false),       // primary hit: no extra call
            repo("notebook", "Jupyter Notebook", false), // primary miss: languages checked, has Java
            repo("docs", null, false),              // no code: never checked
            repo("forked", "Java", true),           // fork: skipped
            repo("web", "TypeScript", false)));     // primary miss: languages checked, no Java
        when(github.languagesOf("o/notebook")).thenReturn(Set.of("jupyter notebook", "java"));
        when(github.languagesOf("o/web")).thenReturn(Set.of("typescript"));

        BulkImportService s = service(Runnable::run);
        ImportJob j = await(s, s.start("o", javaOnly()).id());

        assertThat(j.status()).isEqualTo("succeeded");
        assertThat(j.phase()).isEqualTo("done");
        assertThat(j.totalMatched()).isEqualTo(2);
        assertThat(j.results()).extracting(r -> r.get("fullName")).containsExactly("o/java-app", "o/notebook");
        verify(github, never()).languagesOf("o/java-app");
        verify(github, never()).languagesOf("o/docs");
        verify(github, times(1)).languagesOf("o/web");
        assertThat(j.log()).anyMatch(l -> l.contains("Checking all languages of 2 repos"));
    }

    @Test
    void theSameImportWhileOneRunsIsThatJobAndCancelStopsIt() throws Exception {
        CountDownLatch listing = new CountDownLatch(1);
        when(github.listOrgRepos(anyString(), any())).thenAnswer(inv -> {
            listing.await(5, TimeUnit.SECONDS);
            return List.of(repo("a", "Java", false), repo("b", "Java", false));
        });
        BulkImportService s = service(Executors.newCachedThreadPool());

        ImportJob first = s.start("o", javaOnly());
        ImportJob again = s.start("O", javaOnly());
        assertThat(again.id()).isEqualTo(first.id());
        ImportJob other = s.start("o", new BulkImportRequest(List.of("Go"), List.of(), List.of(), true, true, false, false));
        assertThat(other.id()).isNotEqualTo(first.id());

        assertThat(s.cancel(first.id(), "clicked twice")).isTrue();
        listing.countDown();
        ImportJob done = await(s, first.id());
        assertThat(done.status()).isEqualTo("cancelled");
        assertThat(done.processed()).isZero();
        assertThat(s.cancel(first.id(), null)).isFalse(); // already finished
        assertThat(s.cancel(999, null)).isFalse();
        await(s, other.id());
    }
}
