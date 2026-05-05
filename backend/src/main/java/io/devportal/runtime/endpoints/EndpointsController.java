package io.devportal.runtime.endpoints;

import java.io.IOException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EndpointsController {

    private final EndpointsService endpoints;

    public EndpointsController(EndpointsService endpoints) {
        this.endpoints = endpoints;
    }

    @GetMapping("/api/assets/{id}/endpoints")
    public AssetEndpoints discover(@PathVariable String id) throws IOException, InterruptedException {
        return endpoints.discover(id);
    }
}
