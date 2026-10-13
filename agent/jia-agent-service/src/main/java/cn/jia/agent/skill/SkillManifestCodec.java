package cn.jia.agent.skill;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Set;

/** Verified declarations are metadata, never installation/ACL or execution authorization. */
public final class SkillManifestCodec {
    public static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";
    private static final BigDecimal MAX_SAFE = new BigDecimal("9007199254740991");
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final Set<String> KEYWORDS = Set.of(("$schema $ref $dynamicRef $anchor $dynamicAnchor $defs $comment title description default examples deprecated readOnly writeOnly "
            + "type enum const multipleOf minimum maximum exclusiveMinimum exclusiveMaximum minLength maxLength pattern format "
            + "contentEncoding contentMediaType contentSchema items prefixItems contains minContains maxContains minItems maxItems uniqueItems "
            + "properties patternProperties additionalProperties propertyNames required dependentRequired dependentSchemas minProperties maxProperties "
            + "allOf anyOf oneOf not if then else unevaluatedItems unevaluatedProperties").split(" "));
    private static final Set<String> SINGLE = Set.of("items contains additionalProperties propertyNames not if then else unevaluatedItems unevaluatedProperties contentSchema".split(" "));
    private static final Set<String> LIST = Set.of("allOf anyOf oneOf prefixItems".split(" "));
    private static final Set<String> MAP = Set.of("$defs properties patternProperties dependentSchemas".split(" "));
    private final JsonSchemaFactory factory;
    private final SchemaValidatorsConfig config;
    private final JsonSchema manifestSchema;
    private final JsonSchema metaSchema;

    public SkillManifestCodec() {
        config = new SchemaValidatorsConfig();
        config.setTypeLoose(false);
        config.setEcma262Validator(true);
        config.setFormatAssertionsEnabled(false);
        factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012, builder ->
                builder.schemaLoaders(loaders -> loaders.add(iri -> {
                    // Built-in Draft2020-12 resources only; never fall through to HTTP/file loaders.
                    require(iri.toString().startsWith("classpath:draft/2020-12/"), "SKILL_SCHEMA_REF_FORBIDDEN");
                    return null;
                })));
        metaSchema = factory.getSchema(SchemaLocation.of(DIALECT), config);
        try (var input = SkillManifestCodec.class.getResourceAsStream("skill-manifest-v1.schema.json")) {
            require(input != null, "SKILL_MANIFEST_SCHEMA_MISSING");
            manifestSchema = factory.getSchema(JSON.readTree(input), config);
        } catch (IOException e) { throw failure("SKILL_MANIFEST_SCHEMA_MISSING"); }
    }

    public JsonNode parseJson(byte[] bytes) {
        try { var value = JSON.readTree(bytes); require(value != null, "SKILL_INVALID_JSON"); checkUnicode(value); return value; }
        catch (IOException e) { throw failure("SKILL_INVALID_JSON"); }
    }

    /** Null means ordinary SKILL.md package, not an executable capability. */
    public Manifest parse(byte[] bytes, String skillKey, String skillVersion) {
        if (bytes == null) return null;
        var document = parseJson(bytes);
        require(document.isObject(), "SKILL_MANIFEST_INVALID");
        var executable = ((ObjectNode) document).deepCopy(); executable.remove("costInfo");
        require(manifestSchema.validate(executable).isEmpty(), "SKILL_MANIFEST_INVALID");
        require(document.path("skillKey").asText().equals(skillKey)
                && document.path("skillVersion").asText().equals(skillVersion), "SKILL_IDENTITY_MISMATCH");
        require(validEntrypoint(document.path("entrypoint").asText()), "SKILL_ENTRYPOINT_INVALID");
        require(document.at("/inputs/minItems").asInt() <= document.at("/inputs/maxItems").asInt(), "SKILL_INPUT_BOUNDS_INVALID");
        require(!(document.at("/outputs/dataSchema").isNull() && document.at("/outputs/mimeTypes").isEmpty())
                && !(document.at("/outputs/artifactsRequired").asBoolean() && document.at("/outputs/mimeTypes").isEmpty()), "SKILL_OUTPUTS_INVALID");
        compileSchema(document.get("parametersSchema"));
        if (!document.at("/outputs/dataSchema").isNull()) compileSchema(document.at("/outputs/dataSchema"));
        var normalized = ((ObjectNode) document).deepCopy();
        var cost = normalizeCost(document.get("costInfo"));
        normalized.set("costInfo", cost.value());
        var warnings = normalized.putArray("metadataWarnings");
        if (cost.invalid()) warnings.add("SKILL_COST_INFO_INVALID");
        return new Manifest(normalized, sha256(bytes));
    }

    public JsonSchema compileSchema(JsonNode schema) {
        require(schema != null && schema.isObject() && DIALECT.equals(schema.path("$schema").asText()), "SKILL_SCHEMA_DIALECT");
        checkPortableNumbers(schema); checkSchemaPolicy(schema);
        try {
            require(metaSchema.validate(schema).isEmpty(), "SKILL_SCHEMA_INVALID");
            var compiled = factory.getSchema(schema.deepCopy(), config);
            compiled.initializeValidators(); // Resolve local refs now, not after admission.
            return compiled;
        } catch (SkillMarketplaceException e) { throw e; }
        catch (RuntimeException e) { throw failure("SKILL_SCHEMA_INVALID"); }
    }

    public JsonNode validateParameters(JsonNode schema, byte[] bytes) {
        var value = parseJson(bytes); checkPortableNumbers(value);
        require(compileSchema(schema).validate(value).isEmpty(), "SKILL_PARAMETERS_INVALID");
        return value;
    }

    public Path resolveEntrypoint(Path packageRoot, String entrypoint) {
        require(validEntrypoint(entrypoint), "SKILL_ENTRYPOINT_INVALID");
        try {
            var root = packageRoot.toRealPath(); var candidate = root.resolve(entrypoint); var real = candidate.toRealPath();
            require(real.startsWith(root) && !real.equals(root) && !Files.isSymbolicLink(candidate)
                    && Files.isRegularFile(candidate), "SKILL_ENTRYPOINT_ESCAPE");
            return real;
        } catch (IOException e) { throw failure("SKILL_ENTRYPOINT_ESCAPE"); }
    }

    private static boolean validEntrypoint(String value) {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._/-]*")) return false;
        for (String part : value.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        return true;
    }
    // Security traversal only. All schema and instance validation belongs to networknt.
    private static void checkSchemaPolicy(JsonNode schema) {
        if (schema.isBoolean()) return;
        require(schema.isObject(), "SKILL_SCHEMA_INVALID");
        schema.fields().forEachRemaining(entry -> {
            String key = entry.getKey(); var value = entry.getValue();
            require(KEYWORDS.contains(key), "SKILL_SCHEMA_UNSUPPORTED");
            if (key.equals("$schema")) require(DIALECT.equals(value.asText()), "SKILL_SCHEMA_DIALECT");
            if (key.equals("$ref") || key.equals("$dynamicRef"))
                require(value.isTextual() && value.asText().startsWith("#"), "SKILL_SCHEMA_REF_FORBIDDEN");
            if (SINGLE.contains(key)) checkSchemaPolicy(value);
            if (LIST.contains(key)) { require(value.isArray(), "SKILL_SCHEMA_INVALID"); value.forEach(SkillManifestCodec::checkSchemaPolicy); }
            if (MAP.contains(key)) { require(value.isObject(), "SKILL_SCHEMA_INVALID"); value.forEach(SkillManifestCodec::checkSchemaPolicy); }
        });
    }
    private static void checkPortableNumbers(JsonNode value) {
        if (value.isNumber()) {
            BigDecimal decimal = value.decimalValue();
            if (decimal.stripTrailingZeros().scale() <= 0) require(decimal.abs().compareTo(MAX_SAFE) <= 0, "SKILL_UNSAFE_NUMBER");
        }
        if (value.isContainerNode()) value.forEach(SkillManifestCodec::checkPortableNumbers);
    }
    private static void checkUnicode(JsonNode value) {
        if (value.isTextual()) checkString(value.asText());
        if (value.isObject()) value.fieldNames().forEachRemaining(SkillManifestCodec::checkString);
        if (value.isContainerNode()) value.forEach(SkillManifestCodec::checkUnicode);
    }
    private static void checkString(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) require(++i < value.length() && Character.isLowSurrogate(value.charAt(i)), "SKILL_INVALID_JSON");
            else require(!Character.isLowSurrogate(c), "SKILL_INVALID_JSON");
        }
    }
    private record Cost(ObjectNode value, boolean invalid) {}
    private static Cost normalizeCost(JsonNode raw) {
        var value = JSON.createObjectNode(); value.put("mode", "UNKNOWN");
        var nullable = Set.of("amount", "currency", "unit", "description", "source", "updatedAt"); nullable.forEach(value::putNull);
        if (raw == null) return new Cost(value, false);
        boolean valid = raw.isObject() && Set.of("FREE", "FIXED", "METERED", "UNKNOWN").contains(raw.path("mode").asText());
        if (valid) {
            Iterator<String> names = raw.fieldNames();
            while (names.hasNext()) { String name = names.next(); if (!name.equals("mode") && !nullable.contains(name)) valid = false; }
            for (String name : nullable) if (raw.hasNonNull(name) && !raw.get(name).isTextual()) valid = false;
            if (raw.hasNonNull("amount") && !raw.get("amount").asText().matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")) valid = false;
        }
        if (valid) raw.fields().forEachRemaining(entry -> value.set(entry.getKey(), entry.getValue().deepCopy()));
        return new Cost(value, !valid);
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void require(boolean condition, String code) { SkillMarketplaceException.require(condition, 400, code); }
    private static SkillMarketplaceException failure(String code) { return new SkillMarketplaceException(400, code); }
    public record Manifest(JsonNode document, String manifestSha256) {
        public Manifest { document = document.deepCopy(); }
        @Override public JsonNode document() { return document.deepCopy(); }
    }
}
