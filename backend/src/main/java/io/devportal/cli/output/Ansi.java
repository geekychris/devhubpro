package io.devportal.cli.output;

/**
 * Tiny ANSI helpers — emit raw escape sequences. JLine's ExternalTerminal pipes them
 * straight through the SSH channel to the user's client. Honors the {@code NO_COLOR}
 * environment variable (https://no-color.org).
 */
public final class Ansi {

    private static final boolean ENABLED = System.getenv("NO_COLOR") == null;

    private static final String RESET = "[0m";
    private static final String BOLD  = "[1m";
    private static final String DIM   = "[2m";
    private static final String RED   = "[31m";
    private static final String GREEN = "[32m";
    private static final String YELLOW = "[33m";
    private static final String BLUE  = "[34m";
    private static final String MAGENTA = "[35m";
    private static final String CYAN  = "[36m";
    private static final String GRAY  = "[90m";

    private Ansi() {}

    public static String bold(Object s)    { return wrap(BOLD, s); }
    public static String dim(Object s)     { return wrap(DIM, s); }
    public static String red(Object s)     { return wrap(RED, s); }
    public static String green(Object s)   { return wrap(GREEN, s); }
    public static String yellow(Object s)  { return wrap(YELLOW, s); }
    public static String blue(Object s)    { return wrap(BLUE, s); }
    public static String magenta(Object s) { return wrap(MAGENTA, s); }
    public static String cyan(Object s)    { return wrap(CYAN, s); }
    public static String gray(Object s)    { return wrap(GRAY, s); }

    private static String wrap(String code, Object s) {
        String str = String.valueOf(s);
        return ENABLED ? code + str + RESET : str;
    }

    /**
     * Color a status-like string: succeeded/ok green, failed/error red, running yellow,
     * queued/pending dim. Falls through unstyled for unknown values.
     */
    public static String status(String s) {
        if (s == null) return "";
        return switch (s.toLowerCase()) {
            case "succeeded", "success", "ok", "stable", "live", "running"
                  -> green(s);
            case "failed", "error", "deprecated", "down"
                  -> red(s);
            case "pending", "queued", "experimental", "warn", "warning"
                  -> yellow(s);
            case "cancelled", "skipped", "stopped"
                  -> gray(s);
            default -> s;
        };
    }

    /** Color a numeric severity count: zero gray, non-zero coloured by severity. */
    public static String severity(int n, String level) {
        if (n == 0) return gray("0");
        return switch (level) {
            case "error"        -> red(String.valueOf(n));
            case "warn", "warning" -> yellow(String.valueOf(n));
            default             -> String.valueOf(n);
        };
    }
}
