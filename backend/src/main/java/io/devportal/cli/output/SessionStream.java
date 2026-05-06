package io.devportal.cli.output;

import java.io.OutputStream;
import java.io.PrintStream;

/**
 * A {@link PrintStream} that routes writes to a per-thread target when one is set, falling
 * back to the original {@code System.out} otherwise. Installed once at startup as the new
 * {@code System.out} so all the existing command code that calls {@code System.out.println}
 * automatically writes to the SSH session's terminal — without each command having to thread
 * a writer through.
 */
public final class SessionStream extends PrintStream {

    private static final ThreadLocal<PrintStream> CURRENT = new ThreadLocal<>();

    private final PrintStream fallback;

    public SessionStream(PrintStream fallback) {
        super(fallback);
        this.fallback = fallback;
    }

    /** Bind a per-session stream to this thread (call before running a command). */
    public static void bind(PrintStream session) { CURRENT.set(session); }

    /** Remove the per-thread binding (call after the command finishes). */
    public static void clear() { CURRENT.remove(); }

    private PrintStream target() {
        PrintStream p = CURRENT.get();
        return p != null ? p : fallback;
    }

    @Override public void write(int b) { target().write(b); }
    @Override public void write(byte[] b, int off, int len) { target().write(b, off, len); }
    @Override public void write(byte[] b) throws java.io.IOException { target().write(b); }
    @Override public void flush() { target().flush(); }
    @Override public void close() { target().flush(); /* never close fallback */ }
    @Override public boolean checkError() { return target().checkError(); }
    @Override public void print(boolean v)   { target().print(v); }
    @Override public void print(char v)      { target().print(v); }
    @Override public void print(int v)       { target().print(v); }
    @Override public void print(long v)      { target().print(v); }
    @Override public void print(float v)     { target().print(v); }
    @Override public void print(double v)    { target().print(v); }
    @Override public void print(char[] v)    { target().print(v); }
    @Override public void print(String v)    { target().print(v); }
    @Override public void print(Object v)    { target().print(v); }
    @Override public void println()          { target().println(); }
    @Override public void println(boolean v) { target().println(v); }
    @Override public void println(char v)    { target().println(v); }
    @Override public void println(int v)     { target().println(v); }
    @Override public void println(long v)    { target().println(v); }
    @Override public void println(float v)   { target().println(v); }
    @Override public void println(double v)  { target().println(v); }
    @Override public void println(char[] v)  { target().println(v); }
    @Override public void println(String v)  { target().println(v); }
    @Override public void println(Object v)  { target().println(v); }
    @Override public PrintStream printf(String f, Object... a) { return target().printf(f, a); }
    @Override public PrintStream printf(java.util.Locale l, String f, Object... a) { return target().printf(l, f, a); }
    @Override public PrintStream format(String f, Object... a) { return target().format(f, a); }
    @Override public PrintStream format(java.util.Locale l, String f, Object... a) { return target().format(l, f, a); }
    @Override public PrintStream append(CharSequence cs) { return target().append(cs); }
    @Override public PrintStream append(CharSequence cs, int s, int e) { return target().append(cs, s, e); }
    @Override public PrintStream append(char c) { return target().append(c); }

    /** Wrap an arbitrary OutputStream as a PrintStream for {@link #bind}. */
    public static PrintStream wrap(OutputStream os) { return new PrintStream(os, true, java.nio.charset.StandardCharsets.UTF_8); }
}
