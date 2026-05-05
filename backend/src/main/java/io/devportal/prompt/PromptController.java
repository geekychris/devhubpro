package io.devportal.prompt;

import io.devportal.prompt.dto.HelpPrompt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PromptController {

    private final PromptService prompt;

    public PromptController(PromptService prompt) { this.prompt = prompt; }

    @GetMapping("/api/assets/{id}/help-prompt")
    public HelpPrompt build(
        @PathVariable String id,
        @RequestParam(required = false, defaultValue = "general") String problem,
        @RequestParam(required = false) String details
    ) {
        return prompt.build(id, problem, details);
    }
}
