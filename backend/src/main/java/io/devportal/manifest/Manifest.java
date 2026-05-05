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
        Docs docs
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
        List<Env> env
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
