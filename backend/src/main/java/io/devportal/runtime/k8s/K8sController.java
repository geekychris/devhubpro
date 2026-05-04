package io.devportal.runtime.k8s;

import io.devportal.runtime.k8s.dto.K8sStatus;
import io.devportal.runtime.k8s.dto.MonitoringLinks;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class K8sController {

    private final K8sService k8s;

    public K8sController(K8sService k8s) { this.k8s = k8s; }

    @PostMapping("/api/assets/{id}/k8s/apply")
    public Map<String, Object> apply(@PathVariable String id) throws IOException, InterruptedException {
        return k8s.apply(id);
    }

    @DeleteMapping("/api/assets/{id}/k8s")
    public Map<String, Object> delete(@PathVariable String id) throws IOException, InterruptedException {
        return k8s.delete(id);
    }

    @GetMapping("/api/assets/{id}/k8s/status")
    public K8sStatus status(@PathVariable String id) throws IOException, InterruptedException {
        return k8s.status(id);
    }

    @GetMapping("/api/assets/{id}/k8s/links")
    public MonitoringLinks links(@PathVariable String id) {
        return k8s.links(id);
    }
}
