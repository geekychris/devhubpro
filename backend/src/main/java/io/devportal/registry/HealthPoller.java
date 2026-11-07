package io.devportal.registry;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background health checks for live deployments. Each sweep probes every deployment's
 * {@link Deployment#probeUrl()} (its health URL, else its primary URL while running) with a
 * short timeout, in parallel, on its own threads — API requests never wait on a probe.
 * A sweep that is still running when the next one is due is skipped, not stacked.
 */
@Component
public class HealthPoller {

    private static final Logger log = LoggerFactory.getLogger(HealthPoller.class);

    private final DeploymentRepository repo;
    private final RegistryProperties.Health cfg;
    private final HttpClient http;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore inFlight;
    private final AtomicBoolean sweeping = new AtomicBoolean(false);

    public HealthPoller(DeploymentRepository repo, RegistryProperties props) {
        this.repo = repo;
        this.cfg = props.health();
        this.inFlight = new Semaphore(Math.max(1, cfg.concurrency()));
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(Math.max(200, cfg.timeoutMs())))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .executor(pool)
            .build();
    }

    @Scheduled(fixedDelayString = "${devportal.registry.health.interval-ms:60000}",
               initialDelayString = "${devportal.registry.health.initial-delay-ms:15000}")
    public void scheduledSweep() {
        if (!cfg.enabled()) return;
        sweep();
    }

    /** Probes every pollable live deployment; returns how many were probed. */
    public int sweep() {
        if (!sweeping.compareAndSet(false, true)) return 0;
        try {
            List<Deployment> all = repo.findPollable();
            return probeAll(all);
        } catch (RuntimeException e) {
            log.warn("registry health sweep failed: {}", e.toString());
            return 0;
        } finally {
            sweeping.set(false);
        }
    }

    /** Fire-and-forget checks of just-reported deployments, so new rows don't sit at 'unknown' for a minute. */
    public void checkSoon(Collection<Deployment> ds) {
        if (!cfg.enabled() || ds.isEmpty()) return;
        List<Deployment> copy = List.copyOf(ds);
        pool.submit(() -> {
            try {
                probeAll(copy);
            } catch (RuntimeException e) {
                log.debug("registry immediate check failed: {}", e.toString());
            }
        });
    }

    private int probeAll(List<Deployment> ds) {
        List<Future<?>> futures = new ArrayList<>();
        for (Deployment d : ds) {
            String url = d.probeUrl();
            if (url == null || d.retiredAt() != null) continue;
            futures.add(pool.submit(() -> {
                try {
                    inFlight.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    Result r = probe(url);
                    repo.recordCheck(d.id(), r.status(), r.error(), r.latencyMs());
                } catch (RuntimeException e) {
                    log.debug("registry check of {} failed to record: {}", d.key(), e.toString());
                } finally {
                    inFlight.release();
                }
            }));
        }
        // Bound the sweep: each wave of `concurrency` probes takes at most ~2x the timeout
        // (connect + request), plus slack. Stragglers past that are cancelled.
        int conc = Math.max(1, cfg.concurrency());
        long waves = Math.max(1, (futures.size() + conc - 1) / conc);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos((cfg.timeoutMs() * 2 + 500) * waves + 5_000);
        for (Future<?> f : futures) {
            long left = deadline - System.nanoTime();
            try {
                f.get(Math.max(1, left), TimeUnit.NANOSECONDS);
            } catch (Exception e) {
                f.cancel(true);
            }
        }
        return futures.size();
    }

    record Result(String status, String error, Integer latencyMs) {}

    Result probe(String url) {
        long start = System.nanoTime();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.trim()))
                .timeout(Duration.ofMillis(Math.max(200, cfg.timeoutMs())))
                .header("User-Agent", "devhubpro-registry/1")
                .GET()
                .build();
            HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
            int ms = (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            Deployments.Verdict v = Deployments.classify(res.statusCode());
            return new Result(v.status(), v.error(), ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new Result("unknown", "interrupted", null);
        } catch (IllegalArgumentException bad) {
            return new Result("unknown", "bad URL: " + bad.getMessage(), null);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": " + e.getMessage();
            return new Result("down", msg.length() > 500 ? msg.substring(0, 500) : msg, null);
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
