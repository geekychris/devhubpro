package io.devportal.cli;

import io.devportal.cli.commands.RootCommand;
import io.devportal.cli.output.SessionStream;
import io.devportal.cli.ssh.SshLifecycle;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import picocli.CommandLine;

/**
 * Activates the embedded CLI / SSH server when {@code devportal.cli.enabled=true} (default).
 * Set to false to skip binding the SSH port (e.g. in tests).
 */
@Configuration
@EnableConfigurationProperties(CliProperties.class)
@ConditionalOnProperty(prefix = "devportal.cli", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CliConfig {

    /**
     * Replace System.out / System.err with a thread-aware stream so each SSH session can
     * receive its own command output without commands having to plumb a writer through.
     * The original System.out remains the fallback for any code running outside an SSH
     * session.
     */
    @PostConstruct
    public void redirectStdStreamsToSession() {
        if (!(System.out instanceof SessionStream)) {
            System.setOut(new SessionStream(System.out));
            System.setErr(new SessionStream(System.err));
        }
    }

    /**
     * picocli factory that resolves command classes through Spring so each command can
     * inject services. Falls back to picocli's default factory for non-bean classes
     * (mostly the help/usage built-ins).
     */
    @Bean
    public CommandLine.IFactory picocliSpringFactory(ApplicationContext ctx) {
        CommandLine.IFactory fallback = CommandLine.defaultFactory();
        return new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> cls) throws Exception {
                String[] beanNames = ctx.getBeanNamesForType(cls);
                if (beanNames.length > 0) return ctx.getBean(beanNames[0], cls);
                return fallback.create(cls);
            }
        };
    }

    @Bean
    public SshLifecycle sshLifecycle(CliProperties props, RootCommand root, CommandLine.IFactory factory) {
        return new SshLifecycle(props, root, factory);
    }
}
