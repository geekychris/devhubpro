package io.devportal.cli.commands;

import io.devportal.asset.AssetRepository;
import io.devportal.audit.AuditService;
import io.devportal.audit.dto.AuditReport;
import io.devportal.cli.output.Ansi;
import io.devportal.cli.output.Out;
import io.devportal.port.PortAllocator;
import io.devportal.runtime.endpoints.AssetEndpoints;
import io.devportal.runtime.endpoints.EndpointsService;
import io.devportal.runtime.k8s.K8sService;
import io.devportal.test.FixtureResult;
import io.devportal.test.TestFixtureService;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Composite operations that span multiple services or shell out to the OS. This is the
 * canonical place to add new "macro" commands — anything that orchestrates several
 * existing CLI verbs, scripts a sequence, or interfaces with the OS.
 *
 * <p>How to add a new macro: write a new method on this class (or a sibling
 * {@code @Component @Command} class). Inject whatever services you need via the
 * constructor; for OS work, use {@link #shell(String, java.nio.file.Path)} as a
 * starting point — same pattern other services use.
 */
@Component
@Command(name = "macro", description = "Composite operations and OS-side tasks.")
public class MacroCommands {

    private final K8sService k8s;
    private final PortAllocator allocator;
    private final TestFixtureService fixtures;
    private final EndpointsService endpoints;
    private final AssetRepository assets;
    private final AuditService audit;

    public MacroCommands(K8sService k8s, PortAllocator allocator, TestFixtureService fixtures,
                         EndpointsService endpoints, AssetRepository assets, AuditService audit) {
        this.k8s = k8s;
        this.allocator = allocator;
        this.fixtures = fixtures;
        this.endpoints = endpoints;
        this.assets = assets;
        this.audit = audit;
    }

    @Command(name = "spinup", description = "Allocate ports, kubectl apply, run setup hooks, print endpoints.")
    public Integer spinup(
        @Parameters(paramLabel = "ID") String id,
        @Option(names = "--scope", defaultValue = "k8s-nodeport") String scope
    ) throws Exception {
        System.out.println("[1/4] allocate ports (scope=" + scope + ")");
        var ports = allocator.allocate(id, scope, false);
        ports.forEach(p -> System.out.println("       " + p.slotName() + " -> " + p.port()));

        System.out.println("[2/4] kubectl apply");
        var applyRes = k8s.apply(id);

        System.out.println("[3/4] run setup hooks (runOnApply fixtures)");
        List<FixtureResult> hooks = fixtures.runOnApplyHooks(id);
        for (FixtureResult h : hooks) {
            int rc = h.exitCode() == null ? -1 : h.exitCode();
            System.out.println("       " + h.name() + ": " + (rc == 0 ? "ok" : "exit=" + rc));
        }

        System.out.println("[4/4] endpoints");
        AssetEndpoints ep = endpoints.discover(id);
        for (var e : ep.endpoints()) {
            if (e.hostAccessible() && e.url() != null) {
                System.out.println("       " + (e.live() ? Ansi.green("✓") : Ansi.gray(" ")) + " "
                    + Ansi.cyan(e.url()) + "  (" + Ansi.gray(e.label()) + ")");
            }
        }

        System.out.println(Out.yaml(Map.of("apply", applyRes, "hooks", hooks)));
        return 0;
    }

    @Command(name = "teardown", description = "kubectl delete + release ports.")
    public Integer teardown(
        @Parameters(paramLabel = "ID") String id,
        @Option(names = "--scope", defaultValue = "k8s-nodeport") String scope
    ) throws Exception {
        System.out.println("[1/2] kubectl delete");
        Object delRes = k8s.delete(id);
        System.out.println(Out.yaml(delRes));

        System.out.println("[2/2] release " + scope + " ports");
        int n = allocator.release(id, scope);
        System.out.println("       released " + n);
        return 0;
    }

    @Command(name = "audit-all", description = "Run audit for every asset, summarise findings.")
    public Integer auditAll(@Option(names = "--json") boolean json) {
        record Row(String id, int findings, int errors, int warnings) {}
        List<Row> rows = new ArrayList<>();
        for (var a : assets.findAll(null, null, null)) {
            try {
                AuditReport r = audit.audit(a.id());
                rows.add(new Row(a.id(), r.findings().size(), r.errors(), r.warnings()));
            } catch (Exception e) {
                rows.add(new Row(a.id(), -1, -1, -1));
            }
        }
        rows.sort((x, y) -> Integer.compare(y.findings(), x.findings()));
        if (json) { System.out.println(Out.json(rows)); return 0; }
        System.out.print(Out.tableOf(rows,
            List.of("ASSET", "FINDINGS", "ERRORS", "WARNINGS"),
            List.<java.util.function.Function<Row, ?>>of(
                Row::id,
                r -> r.findings() == 0 ? Ansi.gray("0") : String.valueOf(r.findings()),
                r -> Ansi.severity(r.errors(), "error"),
                r -> Ansi.severity(r.warnings(), "warn"))));
        return 0;
    }

    @Command(name = "sh", description = "Run a shell command in an asset's workspace (one-shot, captures stdout+stderr).")
    public Integer sh(
        @Parameters(paramLabel = "ID") String id,
        @Parameters(paramLabel = "CMD", arity = "1..*", description = "Command + args.") List<String> argv,
        @Option(names = "--timeout-seconds", defaultValue = "60") int timeoutSeconds
    ) throws Exception {
        var asset = assets.findById(id).orElseThrow();
        java.nio.file.Path ws = java.nio.file.Path.of(System.getProperty("user.home"),
            ".devportal", "workspace", asset.id());
        if (!java.nio.file.Files.isDirectory(ws)) {
            System.out.println("workspace not found: " + ws);
            return 2;
        }
        return shell(String.join(" ", argv), ws, timeoutSeconds);
    }

    /**
     * Run a shell command in the given working directory, stream output through the CLI session,
     * and return the process exit code. Reusable by future macros that need OS-level operations.
     */
    static int shell(String command, java.nio.file.Path cwd, int timeoutSeconds) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command)
            .directory(cwd.toFile())
            .redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) System.out.println(line);
        }
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            System.out.println("(timeout after " + timeoutSeconds + "s)");
            return 124;
        }
        return p.exitValue();
    }
}
