package io.devportal.search;

import io.devportal.search.dto.SearchResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchController {

    private final SearchService search;

    public SearchController(SearchService search) { this.search = search; }

    @GetMapping("/api/search")
    public SearchResult search(
        @RequestParam String q,
        @RequestParam(required = false, defaultValue = "true") boolean includeDocs
    ) {
        return search.search(q, includeDocs);
    }
}
