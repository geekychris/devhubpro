package io.devportal.backup;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bind for {@code devportal.backup.*}. */
@ConfigurationProperties(prefix = "devportal.backup")
public class BackupProperties {

    /** Root directory where timestamped backup folders are written. */
    private String dir = System.getProperty("user.home") + "/.devportal/backups";

    /**
     * When the backup directory is inside a git working tree, automatically run
     * {@code git add . && git commit} after each backup. Push is still opt-in per call.
     */
    private boolean autoCommit = true;

    /**
     * Number of timestamped backups to keep on disk under {@link #dir}. Older folders are
     * removed from the working tree (their git history is untouched). 0 disables pruning.
     */
    private int keepLast = 30;

    public String getDir() { return dir; }
    public void setDir(String dir) { this.dir = dir; }
    public boolean isAutoCommit() { return autoCommit; }
    public void setAutoCommit(boolean autoCommit) { this.autoCommit = autoCommit; }
    public int getKeepLast() { return keepLast; }
    public void setKeepLast(int keepLast) { this.keepLast = keepLast; }
}
