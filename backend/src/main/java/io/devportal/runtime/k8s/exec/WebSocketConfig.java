package io.devportal.runtime.k8s.exec;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final PodExecHandler exec;
    private final WorkspaceShellHandler workspaceShell;

    public WebSocketConfig(PodExecHandler exec, WorkspaceShellHandler workspaceShell) {
        this.exec = exec;
        this.workspaceShell = workspaceShell;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(exec, "/ws/assets/*/k8s/pods/*/exec").setAllowedOrigins("*");
        registry.addHandler(workspaceShell, "/ws/assets/*/workspace/exec").setAllowedOrigins("*");
    }
}
