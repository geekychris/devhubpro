package io.devportal.runtime.k8s;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProxyRoutesController {

    private final ProxyRoutesService routes;

    public ProxyRoutesController(ProxyRoutesService routes) {
        this.routes = routes;
    }

    @GetMapping("/api/proxy/routes")
    public ProxyRoutesService.RoutesResponse list() {
        return routes.list();
    }
}
