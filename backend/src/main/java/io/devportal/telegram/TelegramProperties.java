package io.devportal.telegram;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bind for {@code devportal.telegram.*}. */
@ConfigurationProperties(prefix = "devportal.telegram")
public class TelegramProperties {

    private boolean enabled = false;
    private String botTokenFile;
    private String allowlistFile;
    private boolean allowGroups = false;
    /** split | truncate | file — how to handle replies > 4096 chars (Telegram's per-message limit). */
    private String longMessageMode = "split";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBotTokenFile() { return botTokenFile; }
    public void setBotTokenFile(String s) { this.botTokenFile = s; }
    public String getAllowlistFile() { return allowlistFile; }
    public void setAllowlistFile(String s) { this.allowlistFile = s; }
    public boolean isAllowGroups() { return allowGroups; }
    public void setAllowGroups(boolean v) { this.allowGroups = v; }
    public String getLongMessageMode() { return longMessageMode; }
    public void setLongMessageMode(String s) { this.longMessageMode = s; }
}
