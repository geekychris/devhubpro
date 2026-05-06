package io.devportal.cli.commands;

import io.devportal.cli.output.Ansi;
import io.devportal.cli.output.Out;
import io.devportal.dashboard.DashboardController;
import java.util.List;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Dashboard view — DashboardController owns the aggregation logic (Swagger probing, etc.) so
 * we just call its bean rather than re-implementing that here.
 */
@Component
@Command(name = "dashboard", description = "Running services dashboard with Swagger and credential surfaces.")
public class DashboardCommands {

    private final DashboardController dashboard;

    public DashboardCommands(DashboardController dashboard) { this.dashboard = dashboard; }

    @Command(name = "running", description = "Live dashboard entries.")
    public Integer running(@Option(names = "--json") boolean json) {
        List<DashboardController.DashboardEntry> entries = dashboard.running();
        if (json) {
            System.out.println(Out.json(entries));
            return 0;
        }
        System.out.print(Out.tableOf(entries,
            List.of("ASSET", "TYPE", "LIVE", "PIN", "ENDPOINTS", "SWAGGER"),
            List.<java.util.function.Function<DashboardController.DashboardEntry, ?>>of(
                e -> e.asset().id(),
                e -> e.asset().type(),
                e -> e.live() ? Ansi.green("●") : Ansi.gray("○"),
                e -> Boolean.TRUE.equals(e.asset().dashboardPinned()) ? Ansi.cyan("📌") : Ansi.gray("·"),
                e -> e.endpoints().size(),
                e -> e.swaggerUrl() == null ? Ansi.gray("-") : Ansi.cyan(e.swaggerUrl()))));
        return 0;
    }
}
