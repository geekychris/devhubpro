package io.devportal.manifest;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Java mirror of devportal-asset.schema.json. Records are deserialized from YAML by
 * {@link ManifestParser}; validation against the JSON Schema is performed separately.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Manifest(
    String apiVersion,
    String kind,
    Metadata metadata,
    Spec spec
) {

    public record Metadata(
        String id,
        String name,
        String description,
        String owner,
        List<String> tags
    ) {}

    public record Spec(
        String type,
        String language,
        Build build,
        Docker docker,
        Kubernetes kubernetes,
        Runtime runtime,
        List<DependencyRef> dependencies,
        List<ConsumesRef> consumes,
        Docs docs,
        Test test
    ) {}

    /** Test/QA fixtures. Reserved for future expansion (smoke probes, load fixtures, etc). */
    public record Test(
        List<TestFixture> fixtures
    ) {}

    /**
     * One named test-data fixture. The {@code command} is run in the workspace cwd; if it prints
     * a {@code DEVPORTAL_FIXTURE: {json}} line, the JSON is parsed for structured credentials/
     * links/summary that the UI surfaces in a credentials table.
     */
    public record TestFixture(
        String name,
        String description,
        String command,
        // "host" (default) — run on the dev_portal host with workspace cwd.
        // "docker:<image>" — docker run inside the asset's image (future).
        // "pod:<deployment>" — kubectl exec into a running pod (future).
        String runIn,
        // When true the fixture is treated as a setup/lifecycle hook: after a successful
        // `kubectl apply` (when the caller passes ?runHooks=true), all runOnApply fixtures
        // are executed in declaration order, results merged into the apply response.
        Boolean runOnApply,
        // Wait this many seconds for pods to become Ready before running the hook. Default
        // 0 (no wait). Useful for setup hooks that need the cluster to be live.
        Integer waitForPodsSeconds
    ) {}

    public record Build(
        String tool,
        Map<String, String> commands
    ) {}

    public record Docker(
        Boolean enabled,
        String dockerfile,
        String context,
        String image,
        // Multi-image manifests: list of {tag, dockerfile, context} entries to build via the
        // local-images helper. Used by repos like enterprise-social-platform that produce many
        // local images consumed by their k8s manifests with imagePullPolicy: Never.
        List<Image> images
    ) {}

    public record Image(
        String tag,
        String dockerfile,
        String context
    ) {}

    public record Kubernetes(
        Boolean enabled,
        String manifestPath,
        String namespace
    ) {}

    public record Runtime(
        List<Port> ports,
        List<Env> env,
        Proxy proxy
    ) {}

    public record Port(
        String name,
        String protocol,
        String purpose
    ) {}

    public record Env(
        String name,
        @com.fasterxml.jackson.annotation.JsonProperty("default") String defaultValue,
        String description
    ) {}

    public record Proxy(
        String path,
        String portSlot,
        Boolean stripPrefix,
        String host
    ) {}

    public record DependencyRef(
        String id,
        String versionRef
    ) {}

    public record ConsumesRef(
        String id,
        String role
    ) {}

    public record Docs(
        String architecture,
        String build,
        String run,
        String deploy,
        String runbook
    ) {}
}
