package io.devportal.port;

import java.io.IOException;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PortController {

    private final PortAllocator allocator;

    public PortController(PortAllocator allocator) { this.allocator = allocator; }

    @GetMapping("/ports")
    public List<PortReservation> all() { return allocator.listAll(); }

    @GetMapping("/assets/{id}/ports")
    public List<PortReservation> listForAsset(@PathVariable String id) {
        return allocator.listFor(id);
    }

    @PostMapping("/assets/{id}/ports/allocate")
    public List<PortReservation> allocate(
        @PathVariable String id,
        @RequestParam(required = false, defaultValue = "local") String scope,
        @RequestParam(required = false, defaultValue = "false") boolean reallocate
    ) throws IOException {
        return allocator.allocate(id, scope, reallocate);
    }

    @DeleteMapping("/assets/{id}/ports")
    public java.util.Map<String, Object> release(
        @PathVariable String id,
        @RequestParam(required = false, defaultValue = "local") String scope
    ) {
        int released = allocator.release(id, scope);
        return java.util.Map.of("released", released);
    }
}
