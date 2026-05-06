package io.devportal.cli.ssh;

import io.devportal.cli.commands.RootCommand;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.shell.ShellFactory;
import picocli.CommandLine;

/** One {@link DevportalShellSession} per SSH channel — shared root command tree, isolated streams. */
public class DevportalShellFactory implements ShellFactory {

    private final RootCommand root;
    private final CommandLine.IFactory factory;

    public DevportalShellFactory(RootCommand root, CommandLine.IFactory factory) {
        this.root = root;
        this.factory = factory;
    }

    @Override
    public Command createShell(ChannelSession channel) {
        return new DevportalShellSession(root, factory);
    }
}
