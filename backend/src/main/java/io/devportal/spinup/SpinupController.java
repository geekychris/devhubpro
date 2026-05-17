package io.devportal.spinup;

import io.devportal.asset.error.NotFoundException;
import io.devportal.spinup.dto.SpinupJob;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the {@link SpinupService} "spin up" macro.
 *
 * <p>{@code POST /api/assets/{id}/spinup} kicks the chain and returns the job (queued).
 * The frontend polls {@code GET /api/spinup-jobs/{id}} to render live progress.
 */
@RestController
public class SpinupController {

    private final SpinupService service;

    public SpinupController(SpinupService service) {
        this.service = service;
    }

    @PostMapping("/api/assets/{id}/spinup")
    public ResponseEntity<SpinupJob> start(
        @PathVariable String id,
        @RequestParam(name = "skipImageBuild", required = false, defaultValue = "false") boolean skipImageBuild,
        @RequestParam(name = "skipProbe", required = false, defaultValue = "false") boolean skipProbe,
        @RequestParam(name = "includeRuntime", required = false, defaultValue = "false") boolean includeRuntime
    ) {
        SpinupJob job = service.start(id, skipImageBuild, skipProbe, includeRuntime);
        return ResponseEntity.accepted().body(job);
    }

    @GetMapping("/api/spinup-jobs/{id}")
    public SpinupJob get(@PathVariable long id) {
        SpinupJob j = service.get(id);
        if (j == null) throw new NotFoundException("Spinup job " + id + " not found");
        return j;
    }

    @GetMapping("/api/spinup-jobs")
    public List<SpinupJob> list() {
        return service.list();
    }
}
