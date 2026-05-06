package io.devportal.cli.commands;

import io.devportal.cli.output.Ansi;
import io.devportal.cli.output.Out;
import io.devportal.telegram.TelegramService;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Manage the Telegram bot's allowlist + view status from inside the CLI. The bot itself
 * is gated behind {@code devportal.telegram.enabled=true}; commands that need a running
 * bot will degrade gracefully when it's disabled.
 */
@Component
@Command(name = "telegram", description = "Telegram bot status + chat-id allowlist.")
public class TelegramCommands {

    private final ObjectProvider<TelegramService> svcProvider;

    public TelegramCommands(ObjectProvider<TelegramService> svcProvider) {
        this.svcProvider = svcProvider;
    }

    @Command(name = "status", description = "Bot enabled / running / allowlist size.")
    public Integer status() {
        TelegramService svc = svcProvider.getIfAvailable();
        if (svc == null) {
            System.out.println(Ansi.gray("Telegram support not loaded (bean missing)."));
            return 1;
        }
        System.out.print(Out.kv(List.of(
            new String[]{"running",   svc.isRunning() ? Ansi.green("yes") : Ansi.gray("no")},
            new String[]{"allowlist", String.valueOf(svc.allowlistSize()) + " chat id(s)"}
        )));
        return 0;
    }

    @Command(name = "allowlist", description = "List currently authorized chat ids.")
    public Integer allowlist(@picocli.CommandLine.Option(names = "--json") boolean json) {
        TelegramService svc = svcProvider.getIfAvailable();
        if (svc == null) { System.out.println(Ansi.gray("Telegram disabled.")); return 1; }
        List<Long> ids = svc.currentAllowlist();
        if (json) { System.out.println(Out.json(Map.of("allowlist", ids))); return 0; }
        if (ids.isEmpty()) {
            System.out.println(Ansi.gray("(empty — no chat is authorized)"));
            return 0;
        }
        for (Long id : ids) System.out.println(id);
        return 0;
    }

    @Command(name = "allow", description = "Authorize a chat id (write to the allowlist file).")
    public Integer allow(@Parameters(paramLabel = "CHAT_ID") long chatId) throws Exception {
        TelegramService svc = svcProvider.getIfAvailable();
        if (svc == null) { System.out.println(Ansi.gray("Telegram disabled.")); return 1; }
        boolean added = svc.addToAllowlist(chatId);
        System.out.println(added
            ? Ansi.green("authorized ") + chatId
            : Ansi.gray("already authorized: ") + chatId);
        return 0;
    }

    @Command(name = "deny", description = "Remove a chat id from the allowlist.")
    public Integer deny(@Parameters(paramLabel = "CHAT_ID") long chatId) throws Exception {
        TelegramService svc = svcProvider.getIfAvailable();
        if (svc == null) { System.out.println(Ansi.gray("Telegram disabled.")); return 1; }
        boolean removed = svc.removeFromAllowlist(chatId);
        System.out.println(removed
            ? Ansi.green("removed ") + chatId
            : Ansi.gray("not in allowlist: ") + chatId);
        return 0;
    }
}
