package io.devportal.runtime.forward;

import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.ConflictException;
import io.devportal.asset.error.NotFoundException;
import io.devportal.port.PortProperties;
import io.devportal.port.PortRepository;
import io.devportal.runtime.k8s.cluster.ClusterService;
import io.devportal.runtime.k8s.cluster.dto.PodDetail;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Manages {@code kubectl port-forward} subprocesses. Sessions live in memory; restart of the
 * portal kills them (intentional — port-forwards are dev-time only).
 *
 * <p>Each session ties one host port to one pod's containerPort. Host port is auto-allocated from
 * the configured local pool unless explicitly requested.
 */
@Service
@EnableConfigurationProperties({PortProperties.class, io.devportal.runtime.UrlsProperties.class})
public class PortForwardService {

    private static final Logger log = LoggerFactory.getLogger(PortForwardService.class);

    private final AssetRepository assets;
    private final ClusterService cluster;
    private final PortRepository ports;
    private final PortProperties portProps;
    private final io.devportal.runtime.UrlsProperties urls;

    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    public PortForwardService(AssetRepository assets, ClusterService cluster,
                              PortRepository ports, PortProperties portProps,
                              io.devportal.runtime.UrlsProperties urls) {
        this.assets = assets;
        this.cluster = cluster;
        this.ports = ports;
        this.portProps = portProps;
        this.urls = urls;
    }

    public List<PortForwardSession> listForAsset(String assetId) {
        if (!assets.existsById(assetId)) throw new NotFoundException("Asset '" + assetId + "' not found");
        return sessions.values().stream()
            .filter(s -> assetId.equals(s.session.assetId()))
            .map(s -> s.session)
            .toList();
    }

    public List<PortForwardSession> listAll() {
        return sessions.values().stream().map(s -> s.session).toList();
    }

    public PortForwardSession start(String assetId, String podName, int containerPort, Integer requestedHostPort)
            throws IOException, InterruptedException {
        if (!assets.existsById(assetId)) throw new NotFoundException("Asset '" + assetId + "' not found");
        if (containerPort <= 0 || containerPort > 65535) {
            throw new IllegalArgumentException("containerPort out of range: " + containerPort);
        }
        // Resolve namespace from cluster.
        String namespace = null;
        for (PodDetail p : cluster.listPods(assetId)) {
            if (p.name().equals(podName)) { namespace = p.namespace(); break; }
        }
        if (namespace == null) {
            throw new NotFoundException("Pod '" + podName + "' not found for asset '" + assetId + "'");
        }

        int hostPort = requestedHostPort != null ? requestedHostPort : pickHostPort();
        // Conflict check against active sessions and registry's local-scope reservations.
        if (sessions.values().stream().anyMatch(s -> s.session.hostPort() == hostPort && "running".equals(s.session.status()))) {
            throw new ConflictException("Host port " + hostPort + " already used by another port-forward");
        }

        long id = nextId.getAndIncrement();
        ProcessBuilder pb = new ProcessBuilder(
            "kubectl", "port-forward", "-n", namespace, "pod/" + podName,
            hostPort + ":" + containerPort);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        PortForwardSession session = new PortForwardSession(
            id, assetId, namespace, podName, containerPort, hostPort,
            "running", null, Instant.now(), urls.host());
        Session s = new Session(session, p);
        sessions.put(id, s);

        // Pump stdout to the log so we can see early failures.
        Thread.ofVirtual().name("pf-watch-" + id).start(() -> {
            try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log.info("[pf {}] {}", id, line);
                }
            } catch (IOException ignored) {}
            int exit;
            try { exit = p.waitFor(); } catch (InterruptedException e) { return; }
            // Mark stopped/failed.
            Session existing = sessions.get(id);
            if (existing != null) {
                String status = exit == 0 ? "stopped" : "failed";
                String error = exit == 0 ? null : "exit=" + exit;
                sessions.put(id, new Session(
                    new PortForwardSession(
                        existing.session.id(), existing.session.assetId(), existing.session.namespace(),
                        existing.session.podName(), existing.session.containerPort(),
                        existing.session.hostPort(), status, error, existing.session.startedAt(),
                        existing.session.host()),
                    existing.process));
            }
            log.info("port-forward {} exited (code={})", id, exit);
        });

        log.info("Started port-forward {} for {}: localhost:{} -> {}/{}:{}", id, assetId,
            hostPort, namespace, podName, containerPort);
        return session;
    }

    public void stop(long id) {
        Session s = sessions.remove(id);
        if (s == null) throw new NotFoundException("Port-forward " + id + " not found");
        if (s.process.isAlive()) s.process.destroy();
        log.info("Stopped port-forward {}", id);
    }

    @PreDestroy
    public void shutdown() {
        for (Session s : sessions.values()) {
            if (s.process.isAlive()) s.process.destroy();
        }
        sessions.clear();
    }

    private int pickHostPort() {
        Set<Integer> taken = new HashSet<>(ports.portsTakenInScope("local", "tcp"));
        for (Session s : sessions.values()) {
            if ("running".equals(s.session.status())) taken.add(s.session.hostPort());
        }
        for (int p = portProps.local().start(); p <= portProps.local().end(); p++) {
            if (!taken.contains(p)) return p;
        }
        throw new IllegalStateException("Local port pool exhausted");
    }

    private record Session(PortForwardSession session, Process process) {}
}
