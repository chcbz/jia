package cn.jia.chat.service;

import cn.jia.chat.api.ChatTypedInspectionWire;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore;
import cn.jia.chat.handler.TypedInspectionDeclaration;
import cn.jia.chat.handler.TypedInspectionSessionRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Builds the immutable server-owned INSPECT manifest; no locator or secret enters the snapshot. */
@Service
public final class ChatTypedInspectionContextService {
    private static final Set<String> ENVELOPE_KEYS = Set.of(
            "schemaVersion", "contract", "purpose", "typedInspection");
    private static final Set<String> INSPECTION_KEYS = Set.of(
            "schemaVersion", "contract", "purpose", "discussionFacts", "manifest",
            "manifestDigest", "authorizationId");
    private static final Set<String> FACT_KEYS = Set.of(
            "schemaVersion", "referenceMode", "supportedOperations", "availableSources");
    private static final Set<String> FACT_SOURCE_KEYS = Set.of("sourceRefId", "kind", "mediaType");
    private static final Set<String> MANIFEST_KEYS = Set.of(
            "schemaVersion", "purpose", "scope", "profile", "sources");
    private static final Set<String> SCOPE_KEYS = Set.of(
            "tenantId", "ownerJiacn", "clientId", "conversationId", "conversationGeneration",
            "taskId", "assignmentRevision", "requestId", "requestRevision", "targetAgentId");
    private static final Set<String> PROFILE_KEYS = Set.of(
            "profileId", "engineContractId", "enginePolicyDigest", "toolPolicyDigest",
            "inputPolicyDigest");
    private static final Set<String> SOURCE_KEYS = Set.of(
            "sourceRefId", "selector", "mediaKind", "mimeType", "byteLength", "sha256",
            "carrier", "carrierContractDigest");
    private static final Set<String> SELECTOR_KEYS = Set.of(
            "kind", "fileId", "version", "purpose", "assetId", "assetRevision");
    private static final Set<String> SOURCE_KINDS = Set.of(
            "TASK_WORKSPACE_FILE", "CURRENT_CONVERSATION_ASSET");
    private static final Set<String> MEDIA_KINDS = Set.of("text", "image", "audio", "file");
    private static final Set<String> CARRIERS = Set.of(
            "DIRECT_TEXT", "LOCAL_IMAGE", "LOCAL_AUDIO", "PARSED_TEXT");

    public record Scope(String tenantId, String ownerJiacn, String clientId,
            String conversationId, long conversationGeneration, String taskId,
            long assignmentRevision, String requestId, long requestRevision, String targetAgentId) { }

    public record Context(Map<String, Object> typedInspection, String admissionEnvelopeJson,
            List<ChatTypedInspectionWire.SourceSelector> selectors,
            Map<String, Object> frozenDeclaration) {
        public Context {
            typedInspection = Map.copyOf(typedInspection);
            selectors = List.copyOf(selectors);
            frozenDeclaration = Map.copyOf(frozenDeclaration);
        }
    }

    private final JdbcTemplate jdbc;
    private final ChatConversationArchiveStore archive;
    private final TypedInspectionSessionRegistry sessions;
    private final boolean enabled;

    public ChatTypedInspectionContextService(JdbcTemplate jdbc, ChatConversationArchiveStore archive,
            TypedInspectionSessionRegistry sessions,
            @Value("${chat.typed-inspection.enabled:false}") boolean enabled) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.archive = Objects.requireNonNull(archive);
        this.sessions = Objects.requireNonNull(sessions);
        this.enabled = enabled;
    }

    public Context resolve(Scope scope, List<ChatTypedInspectionWire.SourceSelector> requested) {
        if (!enabled) throw unavailable("Typed inspection is disabled");
        validate(scope);
        if (requested == null || requested.isEmpty() || requested.size() > 16) throw invalid();
        TypedInspectionSessionRegistry.Ready ready;
        try {
            ready = sessions.requireSingleReady(new TypedInspectionSessionRegistry.Scope(
                    scope.tenantId(), scope.ownerJiacn(), scope.clientId()), scope.targetAgentId());
        } catch (IllegalStateException unavailable) {
            throw unavailable("Typed inspection runtime is unavailable");
        }

        List<Map<String, Object>> sources = new ArrayList<>();
        Set<String> refs = new HashSet<>();
        for (ChatTypedInspectionWire.SourceSelector selector : requested) {
            Map<String, Object> catalog = "TASK_LINKED_WORKSPACE_VERSION".equals(selector.kind())
                    ? workspace(scope, selector) : asset(scope, selector);
            String sourceRefId = sourceRefId(scope, catalog);
            if (!refs.add(sourceRefId)) throw invalid();
            String mediaKind = text(catalog, "mediaType");
            String mimeType = text(catalog, "contentMimeType");
            TypedInspectionDeclaration.Input carrier;
            try {
                carrier = ready.declaration().requireInput(mediaKind, mimeType);
            } catch (IllegalStateException unsupported) {
                throw unsupported();
            }
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("sourceRefId", sourceRefId);
            source.put("selector", catalog.get("selector"));
            source.put("mediaKind", mediaKind);
            source.put("mimeType", mimeType);
            source.put("byteLength", catalog.get("byteLength"));
            source.put("sha256", catalog.get("contentHash"));
            source.put("carrier", carrier.carrier());
            source.put("carrierContractDigest", carrier.carrierContractDigest());
            sources.add(Collections.unmodifiableMap(source));
        }
        sources.sort(Comparator.comparing(source -> (String) source.get("sourceRefId")));

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("purpose", "INSPECT");
        manifest.put("scope", scopeMap(scope));
        manifest.put("profile", ready.manifestProfile());
        manifest.put("sources", List.copyOf(sources));
        String manifestDigest = ChatDeliberationService.digest(manifest);
        String authorizationId = "inspection_"
                + sha256(scope.requestId() + "\0" + manifestDigest).substring(0, 40);

        List<Map<String, Object>> available = sources.stream().map(source -> Map.<String, Object>of(
                "sourceRefId", source.get("sourceRefId"),
                "kind", sourceKind(((Map<?, ?>) source.get("selector")).get("kind")),
                "mediaType", source.get("mediaKind"))).toList();
        Map<String, Object> discussionFacts = new LinkedHashMap<>();
        discussionFacts.put("schemaVersion", 1);
        discussionFacts.put("referenceMode", "AVAILABLE");
        discussionFacts.put("supportedOperations", List.of("GENERATE_IMAGE", "EDIT_IMAGE"));
        discussionFacts.put("availableSources", available);

        Map<String, Object> typed = new LinkedHashMap<>();
        typed.put("schemaVersion", 1);
        typed.put("contract", ChatTypedInspectionWire.CONTRACT);
        typed.put("purpose", "INSPECT");
        typed.put("discussionFacts", Collections.unmodifiableMap(discussionFacts));
        typed.put("manifest", Collections.unmodifiableMap(manifest));
        typed.put("manifestDigest", manifestDigest);
        typed.put("authorizationId", authorizationId);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", 1);
        envelope.put("contract", ChatTypedInspectionWire.CONTRACT);
        envelope.put("purpose", "INSPECT");
        envelope.put("typedInspection", Collections.unmodifiableMap(typed));
        String envelopeJson = CanonicalContextJson.write(envelope);
        parseEnvelope(envelopeJson); // self-check the exact persisted carrier before admission.
        return new Context(Collections.unmodifiableMap(typed), envelopeJson, requested,
                ready.frozenDeclaration());
    }

    public static Map<String, Object> parseEnvelope(String json) {
        try {
            Object raw = cn.jia.core.util.JsonUtil.getMapper().readValue(json, Map.class);
            Map<String, Object> envelope = exactMap(raw, ENVELOPE_KEYS);
            exactOne(envelope.get("schemaVersion"));
            exactText(envelope.get("contract"), ChatTypedInspectionWire.CONTRACT);
            exactText(envelope.get("purpose"), "INSPECT");
            Map<String, Object> inspection = validateTypedInspection(envelope.get("typedInspection"));
            Map<String, Object> result = new LinkedHashMap<>(envelope);
            result.put("typedInspection", inspection);
            return Collections.unmodifiableMap(result);
        } catch (ChatDeliberationException failure) {
            throw failure;
        } catch (Exception failure) {
            throw persistence("Stored typed inspection envelope is invalid");
        }
    }

    public static Map<String, Object> inspection(String envelope) {
        return map(parseEnvelope(envelope).get("typedInspection"));
    }

    public static Map<String, Object> manifest(String envelope) {
        return map(inspection(envelope).get("manifest"));
    }

    public static List<Map<String, Object>> sources(String envelope) {
        Object raw = manifest(envelope).get("sources");
        if (!(raw instanceof List<?> list)) throw persistence("Stored inspection sources are invalid");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) result.add(map(item));
        return List.copyOf(result);
    }

    static Map<String, Object> validateTypedInspection(Object raw) {
        Map<String, Object> typed = exactMap(raw, INSPECTION_KEYS);
        exactOne(typed.get("schemaVersion"));
        exactText(typed.get("contract"), ChatTypedInspectionWire.CONTRACT);
        exactText(typed.get("purpose"), "INSPECT");

        Map<String, Object> facts = exactMap(typed.get("discussionFacts"), FACT_KEYS);
        exactOne(facts.get("schemaVersion"));
        exactText(facts.get("referenceMode"), "AVAILABLE");
        if (!(facts.get("supportedOperations") instanceof List<?> operations)
                || !operations.equals(List.of("GENERATE_IMAGE", "EDIT_IMAGE"))) {
            throw new IllegalArgumentException();
        }
        if (!(facts.get("availableSources") instanceof List<?> available)) {
            throw new IllegalArgumentException();
        }

        Map<String, Object> manifest = exactMap(typed.get("manifest"), MANIFEST_KEYS);
        exactOne(manifest.get("schemaVersion"));
        exactText(manifest.get("purpose"), "INSPECT");
        validateScope(exactMap(manifest.get("scope"), SCOPE_KEYS));
        validateProfile(exactMap(manifest.get("profile"), PROFILE_KEYS));
        if (!(manifest.get("sources") instanceof List<?> rawSources)
                || rawSources.isEmpty() || rawSources.size() > 16
                || available.size() != rawSources.size()) {
            throw new IllegalArgumentException();
        }
        String prior = null;
        List<Map<String, Object>> normalizedSources = new ArrayList<>();
        for (int index = 0; index < rawSources.size(); index++) {
            Map<String, Object> source = validateSource(rawSources.get(index));
            String id = (String) source.get("sourceRefId");
            if (prior != null && prior.compareTo(id) >= 0) throw new IllegalArgumentException();
            prior = id;
            Map<String, Object> factSource = exactMap(available.get(index), FACT_SOURCE_KEYS);
            exactText(factSource.get("sourceRefId"), id);
            exactText(factSource.get("kind"), sourceKind(
                    map(source.get("selector")).get("kind")));
            exactText(factSource.get("mediaType"), (String) source.get("mediaKind"));
            normalizedSources.add(source);
        }
        Map<String, Object> normalizedManifest = new LinkedHashMap<>(manifest);
        normalizedManifest.put("sources", List.copyOf(normalizedSources));
        String manifestDigest = string(typed.get("manifestDigest"));
        if (!manifestDigest.matches("sha256:[0-9a-f]{64}")
                || !manifestDigest.equals(ChatDeliberationService.digest(normalizedManifest))) {
            throw new IllegalArgumentException();
        }
        String authorizationId = string(typed.get("authorizationId"));
        String requestId = string(map(normalizedManifest.get("scope")).get("requestId"));
        String expectedAuthorization = "inspection_"
                + sha256(requestId + "\0" + manifestDigest).substring(0, 40);
        if (!authorizationId.equals(expectedAuthorization)) throw new IllegalArgumentException();
        Map<String, Object> result = new LinkedHashMap<>(typed);
        result.put("discussionFacts", Collections.unmodifiableMap(new LinkedHashMap<>(facts)));
        result.put("manifest", Collections.unmodifiableMap(normalizedManifest));
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> validateSource(Object raw) {
        Map<String, Object> source = exactMap(raw, SOURCE_KEYS);
        String id = string(source.get("sourceRefId"));
        if (!id.matches("source_[0-9a-f]{40}")) throw new IllegalArgumentException();
        Map<String, Object> selector = exactMap(source.get("selector"), SELECTOR_KEYS);
        validateSelector(selector);
        String media = string(source.get("mediaKind"));
        String mime = string(source.get("mimeType"));
        String bytes = string(source.get("byteLength"));
        String sha = string(source.get("sha256"));
        String carrier = string(source.get("carrier"));
        String carrierDigest = string(source.get("carrierContractDigest"));
        if (!MEDIA_KINDS.contains(media) || !mime.matches("[a-z0-9.+-]+/[a-z0-9.+-]+")
                || !bytes.matches("0|[1-9][0-9]*") || !sha.matches("[0-9a-f]{64}")
                || !CARRIERS.contains(carrier)
                || !carrierDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException();
        }
        if (("DIRECT_TEXT".equals(carrier) && !"text".equals(media))
                || ("LOCAL_IMAGE".equals(carrier) && !"image".equals(media))
                || ("LOCAL_AUDIO".equals(carrier) && !"audio".equals(media))
                || ("PARSED_TEXT".equals(carrier) && !"file".equals(media))) {
            throw new IllegalArgumentException();
        }
        Map<String, Object> result = new LinkedHashMap<>(source);
        result.put("selector", Collections.unmodifiableMap(new LinkedHashMap<>(selector)));
        return Collections.unmodifiableMap(result);
    }

    private Map<String, Object> workspace(Scope scope, ChatTypedInspectionWire.SourceSelector selector) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT v.content_mime_type,v.content_hash,v.byte_length
                FROM agent_personal_workspace_task_file_link l
                JOIN agent_personal_workspace_file f ON f.file_id=l.file_id
                  AND BINARY f.tenant_id=BINARY l.tenant_id
                  AND BINARY f.client_id=BINARY l.client_id
                  AND BINARY f.owner_jiacn=BINARY l.owner_jiacn AND f.state='ACTIVE'
                JOIN agent_personal_workspace_file_version v ON v.file_id=l.file_id AND v.version=l.file_version
                  AND BINARY v.tenant_id=BINARY l.tenant_id
                  AND BINARY v.client_id=BINARY l.client_id
                  AND BINARY v.owner_jiacn=BINARY l.owner_jiacn
                WHERE BINARY l.tenant_id=BINARY ? AND BINARY l.owner_jiacn=BINARY ?
                  AND BINARY l.client_id=BINARY ? AND BINARY l.task_id=BINARY ?
                  AND BINARY l.file_id=BINARY ? AND l.file_version=?
                  AND l.link_role='REFERENCE' AND l.link_state='ACTIVE'
                """, scope.tenantId(), scope.ownerJiacn(), scope.clientId(), scope.taskId(),
                selector.fileId(), Integer.parseInt(selector.version()));
        if (rows.size() != 1) throw missing();
        return catalog("TASK_WORKSPACE_FILE", selector,
                text(rows.getFirst(), "content_mime_type"),
                text(rows.getFirst(), "content_hash"),
                number(rows.getFirst(), "byte_length"), null, null);
    }

    private Map<String, Object> asset(Scope scope, ChatTypedInspectionWire.SourceSelector selector) {
        ChatConversationArchiveStore.Source source = archive.findAuthorizedSourceForUpdate(
                new ChatConversationArchiveStore.Scope(
                        scope.tenantId(), scope.ownerJiacn(), scope.clientId()),
                scope.conversationId(), selector.assetId(), Long.parseLong(selector.assetRevision()),
                scope.targetAgentId());
        if (source == null || source.conversationGeneration() != scope.conversationGeneration()
                || !scope.taskId().equals(source.taskId())) throw missing();
        return catalog("CURRENT_CONVERSATION_ASSET", selector, source.contentMimeType(),
                source.sha256(), source.byteLength(), source.requestId(), source.stepId());
    }

    private static Map<String, Object> catalog(String sourceKind,
            ChatTypedInspectionWire.SourceSelector selector, String mimeType, String sha256,
            long byteLength, String parentRequestId, String parentStepId) {
        if (!SOURCE_KINDS.contains(sourceKind)
                || !mimeType.matches("[a-z0-9.+-]+/[a-z0-9.+-]+")
                || !sha256.matches("[0-9a-f]{64}") || byteLength < 0
                || (parentRequestId == null) != (parentStepId == null)) {
            throw unavailable("Inspection source metadata is invalid");
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sourceKind", sourceKind);
        value.put("selectorKind", selector.kind());
        value.put("selector", ChatTypedInspectionWire.selectorMap(selector));
        value.put("mediaType", mediaKind(mimeType));
        value.put("contentMimeType", mimeType);
        value.put("contentHash", sha256);
        value.put("byteLength", Long.toString(byteLength));
        value.put("parentRequestId", parentRequestId);
        value.put("parentStepId", parentStepId);
        return Collections.unmodifiableMap(value);
    }

    static String sourceRefId(Scope scope, Map<String, Object> catalog) {
        Map<String, Object> source = new LinkedHashMap<>(catalog);
        source.remove("sourceRefId");
        return "source_" + sha256(CanonicalContextJson.write(Map.of(
                "scope", sourceScopeMap(scope), "source", source))).substring(0, 40);
    }

    private static Map<String, Object> sourceScopeMap(Scope scope) {
        return Map.of("tenantId", scope.tenantId(), "ownerJiacn", scope.ownerJiacn(),
                "clientId", scope.clientId(), "conversationId", scope.conversationId(),
                "conversationGeneration", Long.toString(scope.conversationGeneration()));
    }

    private static Map<String, Object> scopeMap(Scope scope) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("tenantId", scope.tenantId());
        value.put("ownerJiacn", scope.ownerJiacn());
        value.put("clientId", scope.clientId());
        value.put("conversationId", scope.conversationId());
        value.put("conversationGeneration", Long.toString(scope.conversationGeneration()));
        value.put("taskId", scope.taskId());
        value.put("assignmentRevision", Long.toString(scope.assignmentRevision()));
        value.put("requestId", scope.requestId());
        value.put("requestRevision", Long.toString(scope.requestRevision()));
        value.put("targetAgentId", scope.targetAgentId());
        return Collections.unmodifiableMap(value);
    }

    private static void validateScope(Map<String, Object> scope) {
        exactText(scope.get("tenantId"), "0");
        exact(scope.get("ownerJiacn"), 50);
        exact(scope.get("clientId"), 50);
        positiveDecimal(scope.get("conversationId"));
        positiveDecimal(scope.get("conversationGeneration"));
        exact(scope.get("taskId"), 100);
        canonicalDecimal(scope.get("assignmentRevision"), false);
        exact(scope.get("requestId"), 100);
        positiveDecimal(scope.get("requestRevision"));
        exact(scope.get("targetAgentId"), 100);
    }

    private static void validateProfile(Map<String, Object> profile) {
        exact(profile.get("profileId"), 100);
        exact(profile.get("engineContractId"), 100);
        for (String key : List.of("enginePolicyDigest", "toolPolicyDigest", "inputPolicyDigest")) {
            if (!string(profile.get(key)).matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException();
            }
        }
    }

    private static void validateSelector(Map<String, Object> selector) {
        String kind = string(selector.get("kind"));
        if ("TASK_LINKED_WORKSPACE_VERSION".equals(kind)) {
            exact(selector.get("fileId"), 100);
            positiveDecimal(selector.get("version"));
            exactText(selector.get("purpose"), "REFERENCE");
            if (selector.get("assetId") != null || selector.get("assetRevision") != null) {
                throw new IllegalArgumentException();
            }
        } else if ("CURRENT_CONVERSATION_ASSET".equals(kind)) {
            if (selector.get("fileId") != null || selector.get("version") != null
                    || selector.get("purpose") != null) throw new IllegalArgumentException();
            exact(selector.get("assetId"), 64);
            positiveDecimal(selector.get("assetRevision"));
        } else {
            throw new IllegalArgumentException();
        }
    }

    private static String sourceKind(Object selectorKind) {
        if ("TASK_LINKED_WORKSPACE_VERSION".equals(selectorKind)) return "TASK_WORKSPACE_FILE";
        if ("CURRENT_CONVERSATION_ASSET".equals(selectorKind)) return "CURRENT_CONVERSATION_ASSET";
        throw new IllegalArgumentException();
    }

    private static String mediaKind(String mime) {
        if (mime.startsWith("text/")) return "text";
        if (mime.startsWith("image/")) return "image";
        if (mime.startsWith("audio/")) return "audio";
        return "file";
    }

    private static void validate(Scope scope) {
        if (scope == null || !"0".equals(scope.tenantId()) || scope.conversationGeneration() < 1
                || scope.assignmentRevision() < 0 || scope.requestRevision() < 1) throw invalid();
        exact(scope.tenantId(), 50);
        exact(scope.ownerJiacn(), 50);
        exact(scope.clientId(), 50);
        positiveDecimal(scope.conversationId());
        exact(scope.taskId(), 100);
        exact(scope.requestId(), 100);
        exact(scope.targetAgentId(), 100);
    }

    private static Map<String, Object> exactMap(Object raw, Set<String> keys) {
        if (!(raw instanceof Map<?, ?> map) || map.size() != keys.size()
                || !map.keySet().stream().allMatch(String.class::isInstance)
                || !map.keySet().equals(keys)) throw new IllegalArgumentException();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put((String) entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)
                || !raw.keySet().stream().allMatch(String.class::isInstance)) {
            throw persistence("Stored inspection object is invalid");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            result.put((String) entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(result);
    }

    private static void exactOne(Object value) {
        if (!(value instanceof Number number)
                || new java.math.BigDecimal(number.toString()).compareTo(java.math.BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException();
        }
    }

    private static void exactText(Object value, String expected) {
        if (!expected.equals(value)) throw new IllegalArgumentException();
    }

    private static void exact(Object value, int max) {
        if (!(value instanceof String text)) throw new IllegalArgumentException();
        exact(text, max);
    }

    private static void exact(String value, int max) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > max
                || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
    }

    private static void positiveDecimal(Object value) {
        canonicalDecimal(value, true);
    }

    private static void canonicalDecimal(Object value, boolean positive) {
        String text = string(value);
        if (!(positive ? text.matches("[1-9][0-9]*") : text.matches("0|[1-9][0-9]*"))) {
            throw new IllegalArgumentException();
        }
        try {
            if (!Long.toString(Long.parseLong(text)).equals(text)) throw new IllegalArgumentException();
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException();
        }
    }

    private static String string(Object value) {
        if (!(value instanceof String text) || !validScalar(text)) throw new IllegalArgumentException();
        return text;
    }

    private static boolean validScalar(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return false;
            } else if (Character.isLowSurrogate(unit)) return false;
        }
        return true;
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) value = row.get(key.toUpperCase());
        if (!(value instanceof String text)) throw unavailable("Inspection source metadata is invalid");
        return text;
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) value = row.get(key.toUpperCase());
        if (!(value instanceof Number number)) throw unavailable("Inspection source metadata is invalid");
        return number.longValue();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static ChatDeliberationException invalid() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST,
                "Typed inspection source selection is invalid");
    }

    private static ChatDeliberationException missing() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                "Typed inspection source is unavailable");
    }

    private static ChatDeliberationException unsupported() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST,
                "Typed inspection source format is unsupported");
    }

    private static ChatDeliberationException unavailable(String message) {
        return new ChatDeliberationException(ChatDeliberationException.Reason.PERSISTENCE_ERROR, message);
    }

    private static ChatDeliberationException persistence(String message) {
        return new ChatDeliberationException(ChatDeliberationException.Reason.PERSISTENCE_ERROR, message);
    }
}
