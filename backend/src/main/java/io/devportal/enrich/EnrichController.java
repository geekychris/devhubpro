package io.devportal.enrich;

import io.devportal.enrich.dto.GitInfo;
import java.io.IOException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EnrichController {

    private final EnrichService enrich;

    public EnrichController(EnrichService enrich) { this.enrich = enrich; }

    @GetMapping("/api/assets/{id}/git-info")
    public GitInfo gitInfo(@PathVariable String id) throws IOException {
        return enrich.gitInfo(id);
    }
}
