package io.devportal.runtime.k8s.exec;

import io.devportal.asset.AssetRepository;
import io.devportal.workspace.WorkspaceService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Bridges a browser WebSocket to a host shell process started in
 * {@code ~/.devportal/workspace/<assetId>}. Whatever branch the workspace is currently checked
 * out to is what the shell sees — so after a {@code commit-render} flow, the user lands directly
 * on the {@code devportal/k8s-...} branch.
 *
 * <p>URL: {@code /ws/assets/{assetId}/workspace/exec?shell=/bin/zsh}.
 */
@Component
public class WorkspaceShellHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceShellHandler.class);

    private final AssetRepository assets;
    private final WorkspaceService workspace;
    private final Map<String, Process> processBySession = new ConcurrentHashMap<>();

    public WorkspaceShellHandler(AssetRepository assets, WorkspaceService workspace) {
        this.assets = assets;
        this.workspace = workspace;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        try {
            String path = session.getUri().getPath();
            String[] parts = path.split("/");
            // [0]"" [1]"ws" [2]"assets" [3]<id> [4]"workspace" [5]"exec"
            if (parts.length < 6) {
                send(session, "ERR: bad path " + path + "\n");
                session.close(CloseStatus.NOT_ACCEPTABLE);
                return;
            }
            String assetId = parts[3];

            String query = session.getUri().getQuery();
            String shell = paramFrom(query, "shell");
            if (shell == null || shell.isBlank()) {
                shell = System.getenv("SHELL");
                if (shell == null || shell.isBlank()) shell = "/bin/zsh";
            }

            if (!assets.existsById(assetId)) {
                send(session, "ERR: asset '" + assetId + "' not found\n");
                session.close(CloseStatus.NOT_ACCEPTABLE);
                return;
            }
            Path ws = workspace.workspaceFor(assetId);
            if (!Files.isDirectory(ws.resolve(".git"))) {
                send(session, "ERR: workspace " + ws + " is not a git checkout — clone first\n");
                session.close(CloseStatus.NOT_ACCEPTABLE);
                return;
            }

            // Wrap the shell in script(1) to give it a real PTY — without one, the shell ignores
            // keystrokes since isatty(stdin)=false. Argument syntax differs between BSD (macOS)
            // and util-linux (Linux); detect at runtime.
            String os = System.getProperty("os.name", "").toLowerCase();
            java.util.List<String> cmd;
            if (os.contains("mac") || os.contains("darwin")) {
                cmd = java.util.List.of("script", "-q", "/dev/null", shell, "-i");
            } else {
                cmd = java.util.List.of("script", "-qfec", shell + " -i", "/dev/null");
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(ws.toFile());
            pb.redirectErrorStream(true);
            pb.environment().put("TERM", "xterm-256color");
            Process proc = pb.start();
            processBySession.put(session.getId(), proc);

            String currentBranch = readCurrentBranch(ws);
            send(session, "$ " + shell + " — " + ws + (currentBranch == null ? "" : " (branch: " + currentBranch + ")") + "\n");

            Thread.ofVirtual().name("ws-shell-stdout-" + session.getId()).start(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    char[] buf = new char[1024];
                    int n;
                    while ((n = r.read(buf)) >= 0) {
                        if (!session.isOpen()) break;
                        send(session, new String(buf, 0, n));
                    }
                } catch (IOException e) {
                    log.debug("ws shell stdout closed: {}", e.getMessage());
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
            log.warn("ws shell setup failed: {}", e.getMessage());
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
            log.debug("ws shell stdin write failed: {}", e.getMessage());
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

    private static String readCurrentBranch(Path ws) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD");
            pb.directory(ws.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                out = sb.toString().trim();
            }
            p.waitFor();
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
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
