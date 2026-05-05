package io.devportal.runtime.forward;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PortForwardController {

    private final PortForwardService forwards;

    public PortForwardController(PortForwardService forwards) { this.forwards = forwards; }

    public record StartRequest(
        @NotBlank String podName,
        @NotNull Integer containerPort,
        Integer hostPort
    ) {}

    @GetMapping("/api/port-forwards")
    public List<PortForwardSession> list() { return forwards.listAll(); }

    @GetMapping("/api/assets/{id}/port-forwards")
    public List<PortForwardSession> listForAsset(@PathVariable String id) {
        return forwards.listForAsset(id);
    }

    @PostMapping("/api/assets/{id}/port-forwards")
    public ResponseEntity<PortForwardSession> start(
        @PathVariable String id,
        @RequestBody StartRequest req
    ) throws IOException, InterruptedException {
        return ResponseEntity.status(201).body(
            forwards.start(id, req.podName(), req.containerPort(), req.hostPort()));
    }

    @DeleteMapping("/api/port-forwards/{id}")
    public ResponseEntity<Void> stop(@PathVariable long id) {
        forwards.stop(id);
        return ResponseEntity.noContent().build();
    }
}
