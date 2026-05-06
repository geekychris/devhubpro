package io.devportal.telegram;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings surface for the Telegram bot — token + allowlist. Mirrors the GitHub PAT
 * settings flow: the full token is never returned, just a status snapshot + preview.
 *
 * <p>The {@code devportal.telegram.enabled} master switch lives in {@code application.yml}
 * and is intentionally not exposed here — flipping it requires restarting the JVM, and
 * mixing UI state with file-system state would make the source-of-truth fuzzy.
 */
@RestController
@RequestMapping("/api/settings/telegram")
public class TelegramSettingsController {

    private final TelegramService telegram;
    private final TelegramProperties props;

    public TelegramSettingsController(TelegramService telegram, TelegramProperties props) {
        this.telegram = telegram;
        this.props = props;
    }

    public record TelegramSettings(
        boolean enabled,
        boolean running,
        boolean hasToken,
        String preview,
        String source,
        int tokenLength,                  // helps the user spot a truncated/whitespace-padded paste
        boolean tokenWellFormed,          // false if the saved value doesn't even look like <digits>:<35+ chars>
        int allowlistCount,
        boolean allowGroups,
        String tokenFile,
        String allowlistFile,
        String longMessageMode
    ) {}

    @GetMapping
    public TelegramSettings status() {
        TelegramService.TokenInfo t = telegram.tokenInfo();
        return new TelegramSettings(
            props.isEnabled(), telegram.isRunning(),
            t.hasToken(), t.preview(), t.source(),
            t.length(), t.wellFormed(),
            telegram.allowlistSize(), props.isAllowGroups(),
            props.getBotTokenFile(), props.getAllowlistFile(),
            props.getLongMessageMode()
        );
    }

    public record SetTokenRequest(@NotBlank String token) {}

    @PutMapping("/token")
    public TelegramSettings setToken(@Valid @RequestBody SetTokenRequest req) throws IOException {
        telegram.setToken(req.token());
        return status();
    }

    @DeleteMapping("/token")
    public TelegramSettings clearToken() throws IOException {
        telegram.clearToken();
        return status();
    }

    @PostMapping("/test")
    public TelegramService.TestResult test() {
        return telegram.testConnection();
    }

    @GetMapping("/allowlist")
    public Map<String, Object> allowlist() {
        return Map.of("chatIds", telegram.currentAllowlist());
    }

    public record AddAllowRequest(@jakarta.validation.constraints.NotNull Long chatId) {}

    @PostMapping("/allowlist")
    public ResponseEntity<Map<String, Object>> add(@Valid @RequestBody AddAllowRequest req) throws IOException {
        boolean added = telegram.addToAllowlist(req.chatId());
        return ResponseEntity.status(added ? 201 : 200).body(Map.of(
            "chatId", req.chatId(), "added", added,
            "chatIds", telegram.currentAllowlist()
        ));
    }

    @DeleteMapping("/allowlist/{chatId}")
    public Map<String, Object> remove(@PathVariable long chatId) throws IOException {
        boolean removed = telegram.removeFromAllowlist(chatId);
        return Map.of("chatId", chatId, "removed", removed,
            "chatIds", telegram.currentAllowlist());
    }

    /** Restart the bot manually — useful when changes happen out-of-band (e.g. file edits). */
    @PostMapping("/restart")
    public TelegramSettings restart() {
        telegram.restart();
        return status();
    }
}
