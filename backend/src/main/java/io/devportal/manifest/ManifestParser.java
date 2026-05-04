package io.devportal.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class ManifestParser {

    private static final Logger log = LoggerFactory.getLogger(ManifestParser.class);
    private static final String SCHEMA_RESOURCE = "schema/devportal-asset.schema.json";

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private JsonSchema schema;

    @PostConstruct
    void loadSchema() throws IOException {
        ClassPathResource resource = new ClassPathResource(SCHEMA_RESOURCE);
        try (InputStream in = resource.getInputStream()) {
            JsonNode schemaNode = jsonMapper.readTree(in);
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            this.schema = factory.getSchema(schemaNode);
            log.info("Loaded devportal asset schema from {}", SCHEMA_RESOURCE);
        }
    }

    /** Parse + validate a YAML manifest. Never throws on invalid input — returns errors in result. */
    public ManifestParseResult parse(String rawYaml) {
        List<ManifestParseResult.ValidationError> errors = new ArrayList<>();
        Manifest manifest = null;
        JsonNode parsed;

        try {
            parsed = yamlMapper.readTree(rawYaml);
        } catch (IOException e) {
            errors.add(new ManifestParseResult.ValidationError("$", "Invalid YAML: " + e.getMessage()));
            return new ManifestParseResult(null, false, errors, rawYaml);
        }

        if (parsed == null || parsed.isMissingNode() || parsed.isNull()) {
            errors.add(new ManifestParseResult.ValidationError("$", "Manifest is empty"));
            return new ManifestParseResult(null, false, errors, rawYaml);
        }

        Set<ValidationMessage> schemaErrors = schema.validate(parsed);
        for (ValidationMessage v : schemaErrors) {
            errors.add(new ManifestParseResult.ValidationError(v.getInstanceLocation().toString(), v.getMessage()));
        }

        try {
            manifest = jsonMapper.treeToValue(parsed, Manifest.class);
        } catch (Exception e) {
            errors.add(new ManifestParseResult.ValidationError("$", "Could not bind to Manifest: " + e.getMessage()));
        }

        return new ManifestParseResult(manifest, errors.isEmpty(), errors, rawYaml);
    }
}
