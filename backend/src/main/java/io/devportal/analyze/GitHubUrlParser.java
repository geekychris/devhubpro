package io.devportal.analyze;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts {@code owner/repo} from common GitHub URL forms. */
public final class GitHubUrlParser {

    private static final Pattern HTTPS = Pattern.compile(
        "^https?://github\\.com/([^/]+)/([^/.]+)(?:\\.git)?/?$");
    private static final Pattern SSH = Pattern.compile(
        "^git@github\\.com:([^/]+)/([^/.]+)(?:\\.git)?$");

    private GitHubUrlParser() {}

    /** Returns "owner/repo" or null if {@code repoUrl} isn't a recognizable GitHub URL. */
    public static String fullName(String repoUrl) {
        if (repoUrl == null) return null;
        String s = repoUrl.trim();
        Matcher m = HTTPS.matcher(s);
        if (m.matches()) return m.group(1) + "/" + m.group(2);
        m = SSH.matcher(s);
        if (m.matches()) return m.group(1) + "/" + m.group(2);
        return null;
    }
}
