package io.devportal.test;

import io.devportal.manifest.Manifest;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestFixtureController {

    private final TestFixtureService fixtures;

    public TestFixtureController(TestFixtureService fixtures) { this.fixtures = fixtures; }

    /** List fixtures declared in the asset's spec.test.fixtures. */
    @GetMapping("/api/assets/{id}/test-fixtures")
    public List<Manifest.TestFixture> list(@PathVariable String id) {
        return fixtures.list(id);
    }

    /** Run one named fixture. Synchronous — returns the parsed result. */
    @PostMapping("/api/assets/{id}/test-fixtures/{name}/run")
    public FixtureResult run(@PathVariable String id, @PathVariable String name) throws IOException {
        return fixtures.run(id, name);
    }

    /** Last-run result for a fixture (404 when never run). */
    @GetMapping("/api/assets/{id}/test-fixtures/{name}/last-run")
    public ResponseEntity<FixtureResult> lastRun(@PathVariable String id, @PathVariable String name) {
        Optional<FixtureResult> r = fixtures.lastRun(id, name);
        return r.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }
}
