package io.devportal.audit;

import io.devportal.audit.dto.AuditReport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuditController {

    private final AuditService service;

    public AuditController(AuditService service) { this.service = service; }

    @GetMapping("/api/assets/{id}/audit")
    public AuditReport audit(@PathVariable String id) {
        return service.audit(id);
    }
}
