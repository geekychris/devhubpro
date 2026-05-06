package io.devportal.cli.ssh;

import io.devportal.cli.commands.RootCommand;
import io.devportal.cli.output.SessionStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

/**
 * One-shot picocli execution for SSH exec channels — i.e. {@code ssh user@host 'asset list'}.
 * Distinct from {@link DevportalShellSession}, which drives an interactive JLine loop.
 *
 * <p>Reuses the same picocli command tree and the same per-thread {@link SessionStream}
 * binding, so command output reaches the SSH client cleanly without cross-talk between
 * concurrent exec requests.
 */
public class DevportalExecCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(DevportalExecCommand.class);

    private final RootCommand root;
    private final CommandLine.IFactory factory;
    private final String commandLine;

    private InputStream in;
    private OutputStream out;
    private OutputStream err;
    private ExitCallback exitCallback;
    private Thread runner;

    public DevportalExecCommand(RootCommand root, CommandLine.IFactory factory, String commandLine) {
        this.root = root;
        this.factory = factory;
        this.commandLine = commandLine;
    }

    @Override public void setInputStream(InputStream in) { this.in = in; }
    @Override public void setOutputStream(OutputStream out) { this.out = out; }
    @Override public void setErrorStream(OutputStream err) { this.err = err; }
    @Override public void setExitCallback(ExitCallback ec) { this.exitCallback = ec; }

    @Override
    public void start(ChannelSession ch, Environment env) {
        runner = new Thread(this::run, "devportal-ssh-exec");
        runner.setDaemon(true);
        runner.start();
    }

    @Override
    public void destroy(ChannelSession ch) {
        if (runner != null) runner.interrupt();
    }

    private void run() {
        int exit = 0;
        PrintStream sessionOut = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream sessionErr = err != null
            ? new PrintStream(err, true, StandardCharsets.UTF_8)
            : sessionOut;
        SessionStream.bind(sessionOut);
        try {
            CommandLine cli = new CommandLine(root, factory);
            cli.setOut(new PrintWriter(sessionOut, true));
            cli.setErr(new PrintWriter(sessionErr, true));
            cli.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
            String[] argv = DevportalShellSession.parseArgv(commandLine);
            exit = cli.execute(argv);
            sessionOut.flush();
            sessionErr.flush();
        } catch (Exception e) {
            log.warn("ssh exec failed: {}", e.getMessage(), e);
            sessionErr.println("error: " + e.getMessage());
            exit = 2;
        } finally {
            SessionStream.clear();
            if (exitCallback != null) exitCallback.onExit(exit);
        }
    }
}
