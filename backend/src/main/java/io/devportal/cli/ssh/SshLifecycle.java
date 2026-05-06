package io.devportal.cli.ssh;

import io.devportal.cli.CliProperties;
import io.devportal.cli.commands.RootCommand;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.HexFormat;
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import picocli.CommandLine;

/**
 * Boots the embedded SSH server alongside Spring. Binds on
 * {@code devportal.cli.ssh.host:port} (default 127.0.0.1:2222), generates a host key on
 * first run, loads {@code authorized_keys} for public-key auth, reads a password file
 * for password auth, and falls back to writing a one-time random password if neither is
 * configured (so the portal is never accidentally world-open *and* never accidentally
 * locked out).
 */
public class SshLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SshLifecycle.class);

    private final CliProperties props;
    private final RootCommand root;
    private final CommandLine.IFactory factory;

    private SshServer server;
    private volatile boolean running;

    public SshLifecycle(CliProperties props, RootCommand root, CommandLine.IFactory factory) {
        this.props = props;
        this.root = root;
        this.factory = factory;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        CliProperties.Ssh cfg = props.getSsh();
        try {
            server = SshServer.setUpDefaultServer();
            server.setHost(cfg.getHost());
            server.setPort(cfg.getPort());

            Path hostKey = expand(cfg.getHostKey());
            Files.createDirectories(hostKey.getParent());
            server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));

            if (cfg.isAllowPublickey()) {
                server.setPublickeyAuthenticator(loadPublickeyAuth(expand(cfg.getAuthorizedKeys())));
            }
            if (cfg.isAllowPassword()) {
                server.setPasswordAuthenticator(loadPasswordAuth(expand(cfg.getPasswordFile()), cfg.isGeneratePasswordIfMissing()));
            }
            server.setShellFactory(new DevportalShellFactory(root, factory));

            server.start();
            running = true;
            log.info("devportal CLI: ssh://{}:{} (publickey={}, password={})",
                cfg.getHost(), cfg.getPort(), cfg.isAllowPublickey(), cfg.isAllowPassword());
        } catch (IOException e) {
            log.error("Failed to start SSH server on {}:{} — CLI will be unavailable",
                cfg.getHost(), cfg.getPort(), e);
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) return;
        try { server.stop(true); }
        catch (IOException e) { log.warn("SSH server stop: {}", e.getMessage()); }
        running = false;
    }

    @Override
    public boolean isRunning() { return running; }

    @Override
    public int getPhase() { return Integer.MAX_VALUE - 1000; } // start late, stop early

    private static Path expand(String s) {
        if (s == null || s.isBlank()) return null;
        if (s.startsWith("~/")) s = System.getProperty("user.home") + s.substring(1);
        return Paths.get(s);
    }

    private PublickeyAuthenticator loadPublickeyAuth(Path authorizedKeysPath) {
        return (username, key, session) -> {
            if (authorizedKeysPath == null || !Files.isReadable(authorizedKeysPath)) return false;
            try {
                for (AuthorizedKeyEntry e : AuthorizedKeyEntry.readAuthorizedKeys(authorizedKeysPath)) {
                    var resolved = e.resolvePublicKey(session, PublicKeyEntryResolver.IGNORING);
                    if (resolved != null && resolved.equals(key)) return true;
                }
            } catch (Exception ex) {
                log.warn("authorized_keys read failed: {}", ex.getMessage());
            }
            return false;
        };
    }

    private PasswordAuthenticator loadPasswordAuth(Path passwordPath, boolean generateIfMissing) {
        if (passwordPath != null && !Files.exists(passwordPath) && generateIfMissing) {
            try {
                Files.createDirectories(passwordPath.getParent());
                String pw = randomPassword();
                Files.writeString(passwordPath, pw + "\n", StandardCharsets.UTF_8);
                try { Files.setPosixFilePermissions(passwordPath, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
                catch (UnsupportedOperationException ignore) { /* non-POSIX FS */ }
                log.warn("devportal CLI: generated SSH password at {}  (cat the file or set your own)", passwordPath);
            } catch (IOException e) {
                log.error("Could not write generated password file at {}: {}", passwordPath, e.getMessage());
            }
        }
        return (username, password, session) -> {
            if (passwordPath == null || !Files.isReadable(passwordPath)) return false;
            try {
                String stored = Files.readString(passwordPath, StandardCharsets.UTF_8).strip();
                return !stored.isEmpty() && stored.equals(password);
            } catch (IOException e) {
                log.warn("password file read failed: {}", e.getMessage());
                return false;
            }
        };
    }

    private static String randomPassword() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
