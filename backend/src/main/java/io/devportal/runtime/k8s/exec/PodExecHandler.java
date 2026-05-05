package io.devportal.runtime.k8s.exec;

import io.devportal.asset.AssetRepository;
import io.devportal.runtime.k8s.cluster.ClusterService;
import io.devportal.runtime.k8s.cluster.dto.PodDetail;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Bridges a browser WebSocket to a {@code kubectl exec -i} subprocess. Each session corresponds
 * to one pod shell. The frontend (xterm.js) sends keystrokes as text frames and receives stdout.
 *
 * <p>URL: {@code /ws/assets/{assetId}/k8s/pods/{pod}/exec?container=&shell=}.
 *
 * <p>Note: this is a streaming proxy, not a TTY. Spring's plain TextWebSocketHandler doesn't have
 * resize support; we run with {@code -i} (no -t) so the shell sees stdin without expecting a tty.
 * Most CLIs work fine; for full curses support, a future iteration could add a terminal-resize
 * control protocol on top of the same socket.
 */
@Component
public class PodExecHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(PodExecHandler.class);

    private final AssetRepository assets;
    private final ClusterService cluster;

    private final Map<String, Process> processBySession = new ConcurrentHashMap<>();

    public PodExecHandler(AssetRepository assets, ClusterService cluster) {
        this.assets = assets;
        this.cluster = cluster;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        try {
            // Path: /ws/assets/{id}/k8s/pods/{pod}/exec
            // Query: container=...&shell=...
            String path = session.getUri().getPath();
            String[] parts = path.split("/");
            // [0]"" [1]"ws" [2]"assets" [3]<id> [4]"k8s" [5]"pods" [6]<pod> [7]"exec"
            if (parts.length < 8) {
                send(session, "ERR: bad path " + path + "\n");
                session.close(CloseStatus.NOT_ACCEPTABLE);
                return;
            }
            String assetId = parts[3];
            String podName = parts[6];

            String query = session.getUri().getQuery();
            String container = paramFrom(query, "container");
            String shell = paramFrom(query, "shell");
            if (shell == null || shell.isBlank()) shell = "/bin/sh";

            if (!assets.existsById(assetId)) {
                send(session, "ERR: asset '" + assetId + "' not found\n");
                session.close(CloseStatus.NOT_ACCEPTABLE);
                return;
            }

            String namespace = null;
            for (PodDetail p : cluster.listPods(assetId)) {
                if (p.name().equals(podName)) { namespace = p.namespace(); break; }
            }
            if (namespace == null) {
                send(session, "ERR: pod '" + podName + "' not found for asset\n");
                session.close(CloseStatus.NOT_ACCEPTABLE);
                return;
            }

            java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of(
                "kubectl", "exec", "-i", "-n", namespace, podName));
            if (container != null && !container.isBlank()) {
                args.add("-c");
                args.add(container);
            }
            args.add("--");
            args.add(shell);

            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            processBySession.put(session.getId(), proc);

            send(session, "$ kubectl exec -n " + namespace + " " + podName
                + (container != null && !container.isBlank() ? " -c " + container : "")
                + " -- " + shell + "\n");

            // Pump stdout asynchronously to the websocket.
            Thread.ofVirtual().name("podexec-stdout-" + session.getId()).start(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    char[] buf = new char[1024];
                    int n;
                    while ((n = r.read(buf)) >= 0) {
                        if (!session.isOpen()) break;
                        send(session, new String(buf, 0, n));
                    }
                } catch (IOException e) {
                    log.debug("stdout read closed: {}", e.getMessage());
                } finally {
                    try {
                        if (session.isOpen()) {
                            send(session, "\n[connection closed]\n");
                            session.close(CloseStatus.NORMAL);
                        }
                    } catch (IOException ignored) {}
                    processBySession.remove(session.getId());
                }
            });
        } catch (Exception e) {
            log.warn("exec ws setup failed: {}", e.getMessage());
            try {
                send(session, "ERR: " + e.getMessage() + "\n");
                session.close(CloseStatus.SERVER_ERROR);
            } catch (IOException ignored) {}
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Process p = processBySession.get(session.getId());
        if (p == null) return;
        try {
            OutputStream stdin = p.getOutputStream();
            stdin.write(message.getPayload().getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException e) {
            log.debug("stdin write failed: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Process p = processBySession.remove(session.getId());
        if (p != null && p.isAlive()) p.destroy();
    }

    private static void send(WebSocketSession session, String text) throws IOException {
        if (session.isOpen()) session.sendMessage(new TextMessage(text));
    }

    private static String paramFrom(String query, String key) {
        if (query == null || query.isEmpty()) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String k = java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            if (k.equals(key)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
