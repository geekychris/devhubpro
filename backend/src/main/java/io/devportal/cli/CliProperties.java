package io.devportal.cli;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bind for {@code devportal.cli.*}. The CLI runs as an embedded SSH server inside the
 * backend JVM; commands inject services directly (no in-process HTTP).
 */
@ConfigurationProperties(prefix = "devportal.cli")
public class CliProperties {

    private boolean enabled = true;
    private Ssh ssh = new Ssh();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Ssh getSsh() { return ssh; }
    public void setSsh(Ssh ssh) { this.ssh = ssh; }

    public static class Ssh {
        private String host = "127.0.0.1";
        private int port = 2222;
        private String hostKey;
        private String authorizedKeys;
        private String passwordFile;
        private boolean allowPublickey = true;
        private boolean allowPassword = true;
        private boolean generatePasswordIfMissing = true;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getHostKey() { return hostKey; }
        public void setHostKey(String hostKey) { this.hostKey = hostKey; }
        public String getAuthorizedKeys() { return authorizedKeys; }
        public void setAuthorizedKeys(String authorizedKeys) { this.authorizedKeys = authorizedKeys; }
        public String getPasswordFile() { return passwordFile; }
        public void setPasswordFile(String passwordFile) { this.passwordFile = passwordFile; }
        public boolean isAllowPublickey() { return allowPublickey; }
        public void setAllowPublickey(boolean allowPublickey) { this.allowPublickey = allowPublickey; }
        public boolean isAllowPassword() { return allowPassword; }
        public void setAllowPassword(boolean allowPassword) { this.allowPassword = allowPassword; }
        public boolean isGeneratePasswordIfMissing() { return generatePasswordIfMissing; }
        public void setGeneratePasswordIfMissing(boolean v) { this.generatePasswordIfMissing = v; }
    }
}
