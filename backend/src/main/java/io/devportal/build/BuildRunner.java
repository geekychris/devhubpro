package io.devportal.build;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Executes a single build step in a workspace, capturing combined stdout+stderr to a log file. */
@Component
public class BuildRunner {

    private static final Logger log = LoggerFactory.getLogger(BuildRunner.class);

    private final BuildRepository builds;

    public BuildRunner(BuildRepository builds) {
        this.builds = builds;
    }

    @Async("buildExecutor")
    public void runAsync(long buildId, Path workspace, String commandLine, String gitSha, Path logPath) {
        run(buildId, workspace, commandLine, gitSha, logPath);
    }

    /** Synchronous variant for orchestrators that already run on a worker thread. */
    public BuildStatus run(long buildId, Path workspace, String commandLine, String gitSha, Path logPath) {
        builds.markRunning(buildId, gitSha);
        BuildStatus result = BuildStatus.FAILED;
        Integer exit = null;

        try {
            Files.createDirectories(logPath.getParent());
            try (PrintWriter logOut = new PrintWriter(Files.newBufferedWriter(logPath, StandardCharsets.UTF_8))) {
                logOut.println("# build " + buildId + " in " + workspace);
                logOut.println("# command: " + commandLine);
                logOut.println("# started: " + Instant.now());
                logOut.println("# ----------");
                logOut.flush();

                ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", commandLine);
                pb.directory(workspace.toFile());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        logOut.println(line);
                        logOut.flush();
                    }
                }
                exit = p.waitFor();
                logOut.println("# ----------");
                logOut.println("# exit: " + exit);
                logOut.println("# finished: " + Instant.now());
                result = exit == 0 ? BuildStatus.SUCCEEDED : BuildStatus.FAILED;
            }
        } catch (IOException | InterruptedException e) {
            log.error("Build {} crashed", buildId, e);
            try {
                Files.writeString(logPath, "\n# CRASHED: " + e.getMessage() + "\n",
                    java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException ignored) {}
            result = BuildStatus.FAILED;
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            builds.markFinished(buildId, result, exit);
        }
        return result;
    }
}
