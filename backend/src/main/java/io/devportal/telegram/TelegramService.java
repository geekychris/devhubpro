package io.devportal.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Chat;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.SendMessage;
import io.devportal.cli.commands.RootCommand;
import io.devportal.cli.output.Out;
import io.devportal.cli.output.SessionStream;
import io.devportal.cli.ssh.DevportalShellSession;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import picocli.CommandLine;

/**
 * Telegram bot bridge. Each incoming message is parsed as a CLI command and executed
 * against the same picocli command tree the SSH CLI uses. Output is captured to a
 * buffer, ANSI escapes are stripped (Telegram clients don't render them), and the
 * result is sent back wrapped in an HTML &lt;pre&gt; block so tables stay aligned.
 *
 * <p>Auth: token in {@link TelegramProperties#getBotTokenFile()}, allowlist of chat
 * ids in {@link TelegramProperties#getAllowlistFile()}. An empty allowlist rejects
 * every message but replies once with the sender's chat id so setup is
 * self-explanatory.
 */
@Service
@EnableConfigurationProperties(TelegramProperties.class)
public class TelegramService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TelegramService.class);
    private static final int TG_MAX = 4096;
    /** ANSI CSI escape sequences — strip before sending to Telegram. */
    private static final java.util.regex.Pattern ANSI = java.util.regex.Pattern.compile("\\[[;\\d]*[ -/]*[@-~]");

    private final TelegramProperties props;
    private final RootCommand root;
    private final CommandLine.IFactory factory;

    private TelegramBot bot;
    private volatile boolean running;
    /** Chat ids we've already replied to with the "you're not authorized" hint, so we don't spam. */
    private final Set<Long> revealedChats = new HashSet<>();

    public TelegramService(TelegramProperties props, RootCommand root, CommandLine.IFactory factory) {
        this.props = props;
        this.root = root;
        this.factory = factory;
    }

    /**
     * Boot-time auto-start gate. {@code devportal.telegram.enabled} controls whether Spring
     * calls {@link #start()} automatically when the context comes up. The UI's Restart button
     * bypasses this — it calls {@link #start()} directly, which only requires a token.
     */
    @Override
    public boolean isAutoStartup() {
        return props.isEnabled();
    }

    @Override
    public synchronized void start() {
        if (running) return;

        String token = readToken();
        if (token == null) {
            log.info("Telegram start requested but no token at {} — set one via the Settings UI"
                + " or scripts/devportal-telegram-setup.sh.", props.getBotTokenFile());
            return;
        }

        bot = new TelegramBot(token);
        bot.setUpdatesListener(updates -> {
            // Visibility for "did anything arrive at all?" — flip to DEBUG once it's working.
            log.info("Telegram: received {} update(s)", updates.size());
            for (Update u : updates) {
                try { handleUpdate(u); }
                catch (Exception e) { log.warn("Telegram update handler failed: {}", e.getMessage(), e); }
            }
            return UpdatesListener.CONFIRMED_UPDATES_ALL;
        }, e -> {
            if (e.response() != null) {
                log.warn("Telegram getUpdates returned {}: {}",
                    e.response().errorCode(), e.response().description());
            } else if (e.getCause() != null) {
                log.warn("Telegram getUpdates IO error: {}", e.getCause().getMessage());
            }
        });
        running = true;
        log.info("Telegram bot listening (allowlist={}, allow-groups={})",
            allowlistSize(), props.isAllowGroups());
    }

    @Override
    public synchronized void stop() {
        if (!running) return;
        try {
            if (bot != null) bot.removeGetUpdatesListener();
            if (bot != null) bot.shutdown();
        } catch (Exception e) {
            log.warn("Telegram shutdown: {}", e.getMessage());
        }
        running = false;
    }

    @Override
    public boolean isRunning() { return running; }

    // ---------- update handling ----------

    private void handleUpdate(Update u) {
        Message msg = u.message();
        if (msg == null) return;
        Chat chat = msg.chat();
        if (chat == null || msg.text() == null) return;

        // Pengrad's Chat.Type enum uses "Private" (capital P) for DMs and lowercase for the
        // group variants — compare case-insensitively to avoid silently dropping every DM.
        boolean isPrivate = chat.type() != null && "private".equalsIgnoreCase(chat.type().name());
        if (!isPrivate && !props.isAllowGroups()) {
            // Silently ignore group messages unless explicitly enabled.
            return;
        }

        long chatId = chat.id();
        if (!isAuthorized(chatId)) {
            // First-touch reveal so the user can copy their chat id into the allowlist.
            if (revealedChats.add(chatId)) {
                send(chatId, "<b>Not authorized.</b>\nYour chat id: <code>" + chatId
                    + "</code>\n\nAdd this id to <code>" + props.getAllowlistFile()
                    + "</code> (one per line) — the bot re-reads the file on every message.");
            }
            log.info("Telegram: rejected message from un-allowlisted chat {} ({})",
                chatId, msg.from() == null ? "?" : msg.from().username());
            return;
        }

        String input = msg.text().trim();
        if (input.isEmpty()) return;
        // Telegram bot commands start with '/'. Strip it so '/asset list' → 'asset list'.
        // Also strip the optional bot suffix that Telegram adds in groups: '/asset@DevportalBot list' → 'asset list'.
        if (input.startsWith("/")) {
            int sp = input.indexOf(' ');
            String head = sp > 0 ? input.substring(1, sp) : input.substring(1);
            int at = head.indexOf('@');
            if (at > 0) head = head.substring(0, at);
            input = sp > 0 ? head + input.substring(sp) : head;
        }
        // /help and /start get nicer custom replies.
        if (input.equals("start")) {
            send(chatId, "<b>devportal</b> — type any CLI command, or <code>help</code> to list groups.");
            return;
        }
        if (input.equals("help")) input = "help";  // picocli's built-in help command

        String reply = executeCommand(input);
        sendChunked(chatId, reply);
    }

    /**
     * Run one CLI command in a captured buffer and return the rendered output.
     * Identical wiring to {@link io.devportal.cli.ssh.DevportalExecCommand}, but the
     * buffer goes back to Telegram instead of an SSH stream.
     */
    private String executeCommand(String input) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(2048);
        PrintStream sessionOut = new PrintStream(buf, true, StandardCharsets.UTF_8);
        SessionStream.bind(sessionOut);
        // Vertical key/value rendering for tables — Telegram clients wrap wide lines.
        Out.bindLayout(Out.Layout.COMPACT);
        try {
            CommandLine cli = new CommandLine(root, factory);
            cli.setOut(new PrintWriter(sessionOut, true));
            cli.setErr(new PrintWriter(sessionOut, true));
            cli.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
            String[] argv = DevportalShellSession.parseArgv(input);
            int rc = cli.execute(argv);
            sessionOut.flush();
            String out = buf.toString(StandardCharsets.UTF_8);
            String stripped = ANSI.matcher(out).replaceAll("");
            if (stripped.isBlank()) return "(no output, exit " + rc + ")";
            return rc == 0 ? stripped : stripped + "\n(exit " + rc + ")";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        } finally {
            Out.clearLayout();
            SessionStream.clear();
        }
    }

    // ---------- output ----------

    /** Send a single message wrapped in a &lt;pre&gt; code block so tables stay aligned. */
    private void send(long chatId, String text) {
        try {
            bot.execute(new SendMessage(chatId, text).parseMode(ParseMode.HTML));
        } catch (Exception e) {
            log.warn("Telegram sendMessage failed: {}", e.getMessage());
        }
    }

    /** Wrap output in a code block, split if it exceeds Telegram's per-message limit. */
    private void sendChunked(long chatId, String body) {
        // Reserve room for <pre>...</pre> wrapper (12 chars) and a "(N/M)" suffix.
        int budget = TG_MAX - 24;
        String escaped = htmlEscape(body);
        switch (props.getLongMessageMode()) {
            case "truncate" -> {
                String slice = escaped.length() > budget
                    ? escaped.substring(0, budget) + "\n…(truncated)"
                    : escaped;
                send(chatId, "<pre>" + slice + "</pre>");
            }
            case "file" -> {
                if (escaped.length() <= budget) {
                    send(chatId, "<pre>" + escaped + "</pre>");
                } else {
                    // Plain text file attachment for very long output.
                    com.pengrad.telegrambot.request.SendDocument doc =
                        new com.pengrad.telegrambot.request.SendDocument(chatId, body.getBytes(StandardCharsets.UTF_8))
                            .fileName("output.txt");
                    bot.execute(doc);
                }
            }
            default -> {  // "split"
                List<String> chunks = chunkOnLineBoundaries(escaped, budget);
                for (int i = 0; i < chunks.size(); i++) {
                    String suffix = chunks.size() > 1 ? "\n(" + (i + 1) + "/" + chunks.size() + ")" : "";
                    send(chatId, "<pre>" + chunks.get(i) + "</pre>" + suffix);
                }
            }
        }
    }

    /** Split into chunks of at most {@code budget} chars, preferring line boundaries. */
    static List<String> chunkOnLineBoundaries(String s, int budget) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            int end = Math.min(i + budget, s.length());
            if (end < s.length()) {
                int lastNl = s.lastIndexOf('\n', end);
                if (lastNl > i) end = lastNl;
            }
            out.add(s.substring(i, end));
            i = end == i ? i + budget : end;
            if (i < s.length() && s.charAt(i) == '\n') i++;  // skip the newline we broke on
        }
        return out.isEmpty() ? List.of(s) : out;
    }

    private static String htmlEscape(String s) {
        // Inside <pre>, only & < > need escaping.
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------- auth ----------

    private String readToken() {
        Path p = expand(props.getBotTokenFile());
        if (p == null || !Files.isReadable(p)) return null;
        try {
            String t = Files.readString(p, StandardCharsets.UTF_8).strip();
            return t.isEmpty() ? null : t;
        } catch (Exception e) { return null; }
    }

    /** Read the allowlist on every message — change takes effect immediately, no restart needed. */
    private boolean isAuthorized(long chatId) {
        Path p = expand(props.getAllowlistFile());
        if (p == null || !Files.isReadable(p)) return false;
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) continue;
                try { if (Long.parseLong(t) == chatId) return true; }
                catch (NumberFormatException ignored) {}
            }
        } catch (Exception ignored) {}
        return false;
    }

    public int allowlistSize() {
        Path p = expand(props.getAllowlistFile());
        if (p == null || !Files.isReadable(p)) return 0;
        try {
            int n = 0;
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) continue;
                try { Long.parseLong(t); n++; } catch (NumberFormatException ignored) {}
            }
            return n;
        } catch (Exception e) { return 0; }
    }

    private static Path expand(String s) {
        if (s == null || s.isBlank()) return null;
        if (s.startsWith("~/")) s = System.getProperty("user.home") + s.substring(1);
        return Paths.get(s);
    }

    // Operations exposed to the CLI.
    public boolean addToAllowlist(long chatId) throws java.io.IOException {
        Path p = expand(props.getAllowlistFile());
        if (p == null) throw new java.io.IOException("allowlist-file not configured");
        Files.createDirectories(p.getParent());
        if (!Files.exists(p)) Files.writeString(p, "");
        for (String line : Files.readAllLines(p)) {
            try { if (Long.parseLong(line.strip()) == chatId) return false; }
            catch (NumberFormatException ignored) {}
        }
        Files.writeString(p, chatId + "\n", StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.APPEND);
        return true;
    }

    public boolean removeFromAllowlist(long chatId) throws java.io.IOException {
        Path p = expand(props.getAllowlistFile());
        if (p == null || !Files.exists(p)) return false;
        List<String> kept = new ArrayList<>();
        boolean removed = false;
        for (String line : Files.readAllLines(p)) {
            String t = line.strip();
            if (!t.isEmpty()) {
                try {
                    if (Long.parseLong(t) == chatId) { removed = true; continue; }
                } catch (NumberFormatException ignored) {}
            }
            kept.add(line);
        }
        if (removed) Files.writeString(p, String.join("\n", kept) + (kept.isEmpty() ? "" : "\n"));
        return removed;
    }

    // ---------- token management (used by the Settings UI / REST controller) ----------

    public record TokenInfo(boolean hasToken, String preview, String source, int length, boolean wellFormed) {}

    /** Inspect the configured token without revealing it. */
    public TokenInfo tokenInfo() {
        String t = readToken();
        if (t == null) return new TokenInfo(false, null, "NONE", 0, false);
        // pengrad bot tokens look like "1234567890:AAH-abcdef0123..." — preview keeps the bot id
        // (the digits before the colon) and a hint of the secret half.
        int colon = t.indexOf(':');
        String preview = (colon > 0 && t.length() > 12)
            ? t.substring(0, colon + 4) + "…" + t.substring(t.length() - 3)
            : "(set)";
        // Detect obvious malformedness so we can warn before a getMe call: a real token has the
        // shape "<digits>:<35-50 base64-ish chars>" and contains no whitespace.
        boolean wellFormed = colon > 0
            && t.substring(0, colon).chars().allMatch(Character::isDigit)
            && t.length() - colon - 1 > 30
            && t.chars().noneMatch(Character::isWhitespace);
        return new TokenInfo(true, preview, "FILE", t.length(), wellFormed);
    }

    /** Persist a new token (mode 0600) and bounce the bot so the change takes effect. */
    public void setToken(String token) throws java.io.IOException {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("token is required");
        Path p = expand(props.getBotTokenFile());
        if (p == null) throw new java.io.IOException("bot-token-file not configured");
        Files.createDirectories(p.getParent());
        Files.writeString(p, token.strip(), StandardCharsets.UTF_8);
        try { Files.setPosixFilePermissions(p,
            java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                 java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)); }
        catch (UnsupportedOperationException ignore) {}
        restart();
    }

    /** Remove the stored token and stop the bot. The application.yml enabled flag is unchanged. */
    public void clearToken() throws java.io.IOException {
        Path p = expand(props.getBotTokenFile());
        if (p != null) Files.deleteIfExists(p);
        stop();
    }

    public record TestResult(boolean ok, String username, String message) {}

    /**
     * Verify the configured token by calling Telegram's getMe over HTTPS — same check
     * the setup script does. Doesn't change any state.
     */
    public TestResult testConnection() {
        String t = readToken();
        if (t == null) return new TestResult(false, null, "no token configured");
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("https://api.telegram.org/bot" + t + "/getMe"))
                .timeout(java.time.Duration.ofSeconds(10))
                .GET().build();
            java.net.http.HttpResponse<String> r = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            String desc = parseField(r.body(), "description");
            if (r.statusCode() == 401) {
                return new TestResult(false, null,
                    "401 Unauthorized" + (desc == null ? "" : " — " + desc)
                        + ". Token (length " + t.length() + ") was rejected. Common causes:"
                        + " typo (compare to @BotFather, char-by-char), token revoked, bot deleted,"
                        + " or whitespace/newline pasted with the token. Check the preview in the UI"
                        + " matches the first/last chars of your @BotFather token.");
            }
            if (r.statusCode() == 404) {
                return new TestResult(false, null,
                    "404 Not Found — Telegram doesn't recognize this bot id. The token's <id>: prefix"
                        + " probably points at a deleted bot. Re-create via @BotFather and try again.");
            }
            if (r.statusCode() / 100 != 2) {
                return new TestResult(false, null,
                    "Telegram returned " + r.statusCode()
                        + (desc == null ? ": " + r.body() : " — " + desc));
            }
            // Cheap regex extract — we only want the username for the success message.
            String u = parseField(r.body(), "username");
            return new TestResult(true, u, u == null ? "ok" : "ok — bot is @" + u);
        } catch (Exception e) {
            return new TestResult(false, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Pull a top-level string field from a Telegram-style JSON response. Tolerant of small variations. */
    private static String parseField(String body, String key) {
        if (body == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /** Restart the bot — picks up a new token without a JVM bounce. */
    public synchronized void restart() {
        if (running) stop();
        start();
    }

    public List<Long> currentAllowlist() {
        Path p = expand(props.getAllowlistFile());
        if (p == null || !Files.isReadable(p)) return List.of();
        List<Long> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(p)) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) continue;
                try { out.add(Long.parseLong(t)); } catch (NumberFormatException ignored) {}
            }
        } catch (Exception ignored) {}
        return out;
    }
}
