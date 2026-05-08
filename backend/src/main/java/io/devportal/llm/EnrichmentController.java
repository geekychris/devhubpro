package io.devportal.llm;

import java.io.IOException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-asset enrichment endpoints. Both return a suggestion the frontend lets the user
 * review/edit before applying via the existing PATCH /api/assets/{id}.
 */
@RestController
public class EnrichmentController {

    private final EnrichmentService service;

    public EnrichmentController(EnrichmentService service) {
        this.service = service;
    }

    @PostMapping("/api/assets/{id}/enrich/description")
    public EnrichmentService.DescriptionSuggestion describe(@PathVariable String id,
            @RequestParam(name = "model", required = false) String model)
            throws IOException, InterruptedException {
        return service.suggestDescription(id, model);
    }

    @PostMapping("/api/assets/{id}/enrich/tags")
    public EnrichmentService.TagSuggestion tags(@PathVariable String id,
            @RequestParam(name = "model", required = false) String model)
            throws IOException, InterruptedException {
        return service.suggestTags(id, model);
    }
}
