package io.devportal.cli.commands;

import io.devportal.analyze.AnalyzeService;
import io.devportal.analyze.AssetArtifact;
import io.devportal.analyze.ValidateService;
import io.devportal.analyze.dto.AnalyzeReport;
import io.devportal.analyze.dto.AutoWireResult;
import io.devportal.analyze.dto.ValidationResult;
import io.devportal.cli.output.Out;
import java.util.List;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "analyze", description = "Manifest validation, pom analysis, auto-wire, artifact catalog.")
public class AnalyzeCommands {

    private final AnalyzeService analyze;
    private final ValidateService validate;

    public AnalyzeCommands(AnalyzeService analyze, ValidateService validate) {
        this.analyze = analyze;
        this.validate = validate;
    }

    @Command(name = "validate", description = "Validate the asset's devportal.yaml against the JSON schema.")
    public Integer validate(@Parameters(paramLabel = "ID") String id) {
        ValidationResult r = validate.validate(id);
        System.out.println(Out.yaml(r));
        return 0;
    }

    @Command(name = "run", description = "Re-analyze the asset's pom (or other build file).")
    public Integer run(@Parameters(paramLabel = "ID") String id) throws Exception {
        AnalyzeReport r = analyze.analyze(id);
        System.out.println(Out.yaml(r));
        return 0;
    }

    @Command(name = "auto-wire", description = "Reconcile dependency edges against current pom contents.")
    public Integer autoWire(@Parameters(paramLabel = "ID") String id) throws Exception {
        AutoWireResult r = analyze.autoWire(id);
        System.out.println(Out.yaml(r));
        return 0;
    }

    @Command(name = "artifacts", description = "Published Maven coordinates for the asset.")
    public Integer artifacts(@Parameters(paramLabel = "ID") String id, @Option(names = "--json") boolean json) {
        List<AssetArtifact> arts = analyze.listArtifacts(id);
        System.out.println(json ? Out.json(arts) : Out.yaml(arts));
        return 0;
    }
}
