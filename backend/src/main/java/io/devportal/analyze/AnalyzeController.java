package io.devportal.analyze;

import io.devportal.analyze.dto.AnalyzeReport;
import io.devportal.analyze.dto.AutoWireResult;
import io.devportal.analyze.dto.ValidationResult;
import java.io.IOException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnalyzeController {

    private final AnalyzeService analyze;
    private final ValidateService validate;

    public AnalyzeController(AnalyzeService analyze, ValidateService validate) {
        this.analyze = analyze;
        this.validate = validate;
    }

    @GetMapping("/api/assets/{id}/validate")
    public ValidationResult validate(@PathVariable String id) {
        return validate.validate(id);
    }

    @PostMapping("/api/assets/{id}/analyze")
    public AnalyzeReport analyze(@PathVariable String id) throws IOException {
        return analyze.analyze(id);
    }

    @PostMapping("/api/assets/{id}/auto-wire")
    public AutoWireResult autoWire(@PathVariable String id) throws IOException {
        return analyze.autoWire(id);
    }

    @GetMapping("/api/assets/{id}/artifacts")
    public List<AssetArtifact> artifacts(@PathVariable String id) {
        return analyze.listArtifacts(id);
    }
}
