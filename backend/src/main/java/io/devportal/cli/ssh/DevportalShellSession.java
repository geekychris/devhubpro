package io.devportal.cli.ssh;

import io.devportal.cli.commands.RootCommand;
import io.devportal.cli.output.Ansi;
import io.devportal.cli.output.SessionStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.ExternalTerminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.shell.jline3.PicocliCommands;

/**
 * One SSH channel = one interactive picocli shell. JLine drives the readline loop with
 * tab completion (powered by picocli's command tree); each typed line is parsed and
 * executed by picocli, with output written back through the SSH session streams.
 */
public class DevportalShellSession implements Command {

    private static final Logger log = LoggerFactory.getLogger(DevportalShellSession.class);
    private static final String PROMPT = Ansi.bold(Ansi.cyan("devportal")) + Ansi.bold("> ");

    private final RootCommand root;
    private final CommandLine.IFactory factory;

    private InputStream in;
    private OutputStream out;
    private OutputStream err;
    private ExitCallback exitCallback;
    private Thread runner;

    public DevportalShellSession(RootCommand root, CommandLine.IFactory factory) {
        this.root = root;
        this.factory = factory;
    }

    @Override public void setInputStream(InputStream in) { this.in = in; }
    @Override public void setOutputStream(OutputStream out) { this.out = out; }
    @Override public void setErrorStream(OutputStream err) { this.err = err; }
    @Override public void setExitCallback(ExitCallback ec) { this.exitCallback = ec; }

    @Override
    public void start(ChannelSession ch, Environment env) {
        runner = new Thread(this::run, "devportal-ssh-shell");
        runner.setDaemon(true);
        runner.start();
    }

    @Override
    public void destroy(ChannelSession ch) {
        if (runner != null) runner.interrupt();
    }

    private void run() {
        int exit = 0;
        // ExternalTerminal directly — TerminalBuilder.system(false).streams(...) ends up choosing
        // PosixPtyTerminal in JLine 3.27 (which allocates a real OS PTY and a pump thread that
        // races with SSH channel close). ExternalTerminal is a pure stream wrapper that's the
        // right primitive for SSH — no PTY, no JNA, no native deps.
        try (Terminal terminal = new ExternalTerminal(
                "devportal-ssh", "xterm-256color", in, out, StandardCharsets.UTF_8)) {

            CommandLine cli = new CommandLine(root, factory);
            cli.setOut(new PrintWriter(terminal.writer(), true));
            cli.setErr(new PrintWriter(terminal.writer(), true));
            // System.console() is null over SSH, so picocli's Ansi.AUTO would disable colors.
            // Force ON: our terminal type is xterm-256color and the client decoded ANSI fine.
            cli.setColorScheme(picocli.CommandLine.Help.defaultColorScheme(picocli.CommandLine.Help.Ansi.ON));

            // Tab completion across the whole picocli command tree.
            PicocliCommands picocliCommands = new PicocliCommands(cli);
            LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(picocliCommands.compileCompleters())
                .build();
            reader.setVariable(LineReader.HISTORY_FILE,
                System.getProperty("user.home") + "/.devportal/cli-history");

            terminal.writer().println(banner());
            terminal.writer().println("type 'help' to list commands, 'help <command>' for detail, ctrl-D or 'exit' to leave.");
            terminal.writer().flush();

            while (true) {
                String line;
                try {
                    line = reader.readLine(PROMPT);
                } catch (UserInterruptException e) {
                    continue;
                } catch (EndOfFileException e) {
                    break;
                }
                if (line == null) break;
                line = line.strip();
                if (line.isEmpty()) continue;
                if (line.equals("exit") || line.equals("quit")) break;

                String[] argv = parseArgv(line);
                java.io.PrintStream sessionOut = SessionStream.wrap(terminal.output());
                SessionStream.bind(sessionOut);
                try {
                    int rc = cli.execute(argv);
                    sessionOut.flush();
                    if (rc != 0) {
                        terminal.writer().println("(exit " + rc + ")");
                        terminal.writer().flush();
                    }
                } catch (Exception ex) {
                    terminal.writer().println("error: " + ex.getMessage());
                    terminal.writer().flush();
                } finally {
                    SessionStream.clear();
                }
            }
        } catch (IOException e) {
            log.warn("ssh shell terminated with i/o error: {}", e.getMessage());
            exit = 1;
        } catch (Exception e) {
            log.warn("ssh shell crashed: {}", e.getMessage(), e);
            exit = 2;
        } finally {
            if (exitCallback != null) exitCallback.onExit(exit);
        }
    }

    private static String banner() {
        return "\n  " + Ansi.bold(Ansi.cyan("devportal")) + " " + Ansi.gray("— embedded ssh cli")
             + "\n  " + Ansi.dim("─────────────────────────────") + "\n";
    }

    /**
     * Bourne-ish argv split — supports double quotes, single quotes, and backslash escapes.
     * Adequate for the CLI; not a full shell parser (no env-var expansion, no pipes).
     */
    public static String[] parseArgv(String line) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false, inDouble = false, has = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length() && !inSingle) {
                cur.append(line.charAt(++i));
                has = true;
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                has = true;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                has = true;
            } else if (Character.isWhitespace(c) && !inSingle && !inDouble) {
                if (has) { out.add(cur.toString()); cur.setLength(0); has = false; }
            } else {
                cur.append(c);
                has = true;
            }
        }
        if (has) out.add(cur.toString());
        return out.toArray(String[]::new);
    }
}
