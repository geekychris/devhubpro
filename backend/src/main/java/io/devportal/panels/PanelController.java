package io.devportal.panels;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PanelController {

    private final PanelService service;

    public PanelController(PanelService service) { this.service = service; }

    @GetMapping("/api/assets/{id}/panels")
    public List<Panel> panels(@PathVariable String id) {
        return service.panelsFor(id);
    }
}
