package io.devportal.cli.commands;

import io.devportal.cli.output.Out;
import io.devportal.port.PortAllocator;
import io.devportal.port.PortReservation;
import java.util.List;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "port", description = "Port registry — allocate, release, inspect.")
public class PortCommands {

    private final PortAllocator allocator;

    public PortCommands(PortAllocator allocator) { this.allocator = allocator; }

    @Command(name = "list", description = "Every port reservation across all scopes.")
    public Integer list(@Option(names = "--json") boolean json) {
        List<PortReservation> ports = allocator.listAll();
        System.out.print(json ? Out.json(ports) + "\n" : Out.tableOf(ports,
            List.of("ASSET", "SLOT", "SCOPE", "PORT", "PROTO"),
            List.of(PortReservation::assetId, PortReservation::slotName, PortReservation::scope,
                PortReservation::port, PortReservation::protocol)));
        return 0;
    }

    @Command(name = "for", description = "Reservations for one asset.")
    public Integer forAsset(@Parameters(paramLabel = "ID") String id, @Option(names = "--json") boolean json) {
        List<PortReservation> ports = allocator.listFor(id);
        System.out.print(json ? Out.json(ports) + "\n" : Out.tableOf(ports,
            List.of("SLOT", "SCOPE", "PORT", "PROTO"),
            List.of(PortReservation::slotName, PortReservation::scope, PortReservation::port, PortReservation::protocol)));
        return 0;
    }

    @Command(name = "allocate", description = "Allocate ports for an asset's slots.")
    public Integer allocate(
        @Parameters(paramLabel = "ID") String id,
        @Option(names = "--scope", defaultValue = "local", description = "local | k8s-nodeport") String scope,
        @Option(names = "--reallocate", description = "Wipe existing reservations first.") boolean reallocate
    ) throws Exception {
        List<PortReservation> ports = allocator.allocate(id, scope, reallocate);
        System.out.print(Out.tableOf(ports,
            List.of("SLOT", "SCOPE", "PORT", "PROTO"),
            List.of(PortReservation::slotName, PortReservation::scope, PortReservation::port, PortReservation::protocol)));
        return 0;
    }

    @Command(name = "release", description = "Release reservations in one scope.")
    public Integer release(
        @Parameters(paramLabel = "ID") String id,
        @Option(names = "--scope", defaultValue = "local") String scope
    ) {
        int n = allocator.release(id, scope);
        System.out.println("released " + n + " reservation(s) in " + scope);
        return 0;
    }
}
