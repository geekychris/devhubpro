package io.devportal.runtime.docker;

import io.devportal.build.dto.BuildView;
import io.devportal.runtime.docker.dto.DockerContainerView;
import io.devportal.runtime.docker.dto.RunContainerResult;
import java.io.IOException;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DockerController {

    private final DockerService docker;

    public DockerController(DockerService docker) { this.docker = docker; }

    @PostMapping("/api/assets/{id}/docker/build")
    public ResponseEntity<BuildView> build(@PathVariable String id) throws IOException {
        return ResponseEntity.status(202).body(docker.buildImage(id));
    }

    @PostMapping("/api/assets/{id}/docker/run")
    public ResponseEntity<RunContainerResult> run(@PathVariable String id) throws IOException, InterruptedException {
        return ResponseEntity.ok(docker.runContainer(id));
    }

    @GetMapping("/api/assets/{id}/docker/containers")
    public List<DockerContainerView> list(@PathVariable String id) throws IOException, InterruptedException {
        return docker.listContainers(id);
    }

    @DeleteMapping("/api/assets/{id}/docker/containers/{name}")
    public ResponseEntity<Void> stop(@PathVariable String id, @PathVariable String name)
        throws IOException, InterruptedException {
        docker.stopAndRemove(name);
        return ResponseEntity.noContent().build();
    }
}
