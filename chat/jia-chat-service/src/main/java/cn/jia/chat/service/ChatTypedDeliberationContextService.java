package cn.jia.chat.service;

import cn.jia.chat.api.ChatTypedDeliberationWire;
import cn.jia.chat.config.ChatTypedDeliberationSchemaInitializer;
import cn.jia.chat.handler.TypedDeliberationSessionRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

/** Builds server-trusted typed facts and an independently persisted private source catalogue. */
@Service
public final class ChatTypedDeliberationContextService {
    public record Scope(String tenantId, String ownerJiacn, String clientId,
            String conversationId, long conversationGeneration) { }
    public record Context(Map<String,Object> facts, String sourceCatalogJson,
            List<ChatTypedDeliberationWire.SourceSelector> selectors,
            Map<String,Object> frozenDeclaration) {
        public Context { facts=Map.copyOf(facts); selectors=List.copyOf(selectors); frozenDeclaration=Map.copyOf(frozenDeclaration); }
    }

    /** Internal classification only; the HTTP reason/message and payload remain unchanged. */
    public static final class RuntimeNotReadyException extends ChatDeliberationException {
        private RuntimeNotReadyException(TypedDeliberationSessionRegistry.RuntimeNotReadyException cause) {
            super(Reason.PERSISTENCE_ERROR, "Typed deliberation runtime is unavailable", cause);
        }
    }

    private final JdbcTemplate jdbc;
    private final TypedDeliberationSessionRegistry sessions;
    private final ChatTypedDeliberationSchemaInitializer schema;
    private final boolean enabled;
    private final ChatActionCapabilityService capabilities;

    public ChatTypedDeliberationContextService(JdbcTemplate jdbc, TypedDeliberationSessionRegistry sessions,
            ChatTypedDeliberationSchemaInitializer schema, ChatActionCapabilityService capabilities,
            @Value("${chat.typed-deliberation.enabled:false}") boolean enabled) {
        this.jdbc=Objects.requireNonNull(jdbc); this.sessions=Objects.requireNonNull(sessions);
        this.schema=Objects.requireNonNull(schema); this.enabled=enabled;
        this.capabilities=Objects.requireNonNull(capabilities);
    }

    /** A pre-claim lifecycle gate, not an alternative to resolve's identity/source checks. */
    public ChatTypedDeliberationSchemaInitializer.Readiness bootstrapReadiness() {
        return enabled ? schema.readiness() : ChatTypedDeliberationSchemaInitializer.Readiness.DISABLED;
    }

    public Context resolve(Scope scope, String taskId, String targetAgentId,
            List<ChatTypedDeliberationWire.SourceSelector> requested) {
        if (!enabled || !schema.ready()) throw unavailable("Typed deliberation schema is unavailable");
        exact(scope.tenantId(),50); exact(scope.ownerJiacn(),50); exact(scope.clientId(),50);
        exact(scope.conversationId(),100); exact(taskId,100); exact(targetAgentId,100);
        if (scope.conversationGeneration()<1 || requested==null || requested.size()>32) throw invalid();
        TypedDeliberationSessionRegistry.Ready ready;
        try {
            ready=sessions.requireSingleReady(new TypedDeliberationSessionRegistry.Scope(
                    scope.tenantId(),scope.ownerJiacn(),scope.clientId()),targetAgentId);
        } catch (TypedDeliberationSessionRegistry.RuntimeNotReadyException awaiting) {
            throw new RuntimeNotReadyException(awaiting);
        } catch (IllegalStateException unavailable) {
            throw unavailable("Typed deliberation runtime is unavailable");
        }
        List<Map<String,Object>> catalog=new ArrayList<>();
        List<Map<String,Object>> available=new ArrayList<>();
        List<ChatTypedDeliberationWire.SourceSelector> normalized=new ArrayList<>();
        Set<String> sourceRefs=new HashSet<>();
        for (var raw:requested) {
            var selector=ChatTypedDeliberationWire.validateSelector(raw);
            Map<String,Object> entry="TASK_LINKED_WORKSPACE_VERSION".equals(selector.kind())
                    ? workspace(scope,taskId,selector) : asset(scope,taskId,targetAgentId,selector);
            String sourceRef=sourceRefId(scope,entry);
            if (!sourceRefs.add(sourceRef)) throw invalid();
            Map<String,Object> stored=new LinkedHashMap<>(entry); stored.put("sourceRefId",sourceRef);
            catalog.add(Collections.unmodifiableMap(new LinkedHashMap<>(stored)));
            available.add(Map.of("sourceRefId",sourceRef,"kind",entry.get("sourceKind"),
                    "mediaType",entry.get("mediaType")));
            normalized.add(selector);
        }
        catalog.sort(Comparator.comparing(value -> (String)value.get("sourceRefId")));
        available.sort(Comparator.comparing(value -> (String)value.get("sourceRefId")));
        Map<String,Object> facts=new LinkedHashMap<>(); facts.put("schemaVersion",3);
        facts.put("availableSources",List.copyOf(available));
        facts.put("inspectedSourceRefIds",List.of());
        facts.put("availableActions",capabilities.available(new ChatActionCapabilityService.Scope(
                scope.tenantId(),scope.ownerJiacn(),scope.clientId(),targetAgentId),List.copyOf(catalog)));
        ChatActionOutcomeContract.facts(facts);
        return new Context(Map.copyOf(facts),CanonicalContextJson.write(catalog),normalized,ready.frozenDeclaration());
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String,Object>> parseCatalog(String json) {
        try {
            Object value=cn.jia.core.util.JsonUtil.getMapper().readValue(json,List.class);
            if (!(value instanceof List<?> list)) throw new IllegalArgumentException();
            List<Map<String,Object>> result=new ArrayList<>();
            for (Object item:list) {
                if (!(item instanceof Map<?,?> map) || !map.keySet().stream().allMatch(String.class::isInstance)) throw new IllegalArgumentException();
                result.add(Collections.unmodifiableMap(new LinkedHashMap<>((Map<String,Object>)map)));
            }
            return List.copyOf(result);
        } catch (Exception error) { throw unavailable("Stored typed source catalogue is invalid"); }
    }

    private Map<String,Object> workspace(Scope s,String taskId,ChatTypedDeliberationWire.SourceSelector x) {
        List<Map<String,Object>> rows=jdbc.queryForList("""
                SELECT v.content_mime_type,v.content_hash,v.byte_length
                FROM agent_personal_workspace_task_file_link l
                JOIN agent_personal_workspace_file f ON f.file_id=l.file_id AND f.tenant_id=l.tenant_id
                  AND BINARY f.tenant_id=BINARY l.tenant_id AND f.client_id=l.client_id
                  AND BINARY f.client_id=BINARY l.client_id AND f.owner_jiacn=l.owner_jiacn
                  AND BINARY f.owner_jiacn=BINARY l.owner_jiacn AND f.state='ACTIVE'
                JOIN agent_personal_workspace_file_version v ON v.file_id=l.file_id AND v.version=l.file_version
                  AND v.tenant_id=l.tenant_id AND BINARY v.tenant_id=BINARY l.tenant_id
                  AND v.client_id=l.client_id AND BINARY v.client_id=BINARY l.client_id
                  AND v.owner_jiacn=l.owner_jiacn AND BINARY v.owner_jiacn=BINARY l.owner_jiacn
                WHERE l.tenant_id=? AND BINARY l.tenant_id=BINARY ?
                  AND l.owner_jiacn=? AND BINARY l.owner_jiacn=BINARY ?
                  AND l.client_id=? AND BINARY l.client_id=BINARY ?
                  AND l.task_id=? AND BINARY l.task_id=BINARY ?
                  AND l.file_id=? AND BINARY l.file_id=BINARY ? AND l.file_version=?
                  AND l.link_role=? AND BINARY l.link_role=BINARY ? AND l.link_state='ACTIVE'
                """,s.tenantId(),s.tenantId(),s.ownerJiacn(),s.ownerJiacn(),s.clientId(),s.clientId(),
                taskId,taskId,x.fileId(),x.fileId(),Integer.parseInt(x.version()),x.purpose(),x.purpose());
        if(rows.size()!=1)throw missing(); Map<String,Object> row=rows.getFirst();
        return source("TASK_WORKSPACE_FILE","TASK_LINKED_WORKSPACE_VERSION",x,
                text(row,"content_mime_type"),text(row,"content_hash"),number(row,"byte_length"),null,null);
    }

    private Map<String,Object> asset(Scope s,String taskId,String targetAgentId,ChatTypedDeliberationWire.SourceSelector x) {
        List<Map<String,Object>> rows=jdbc.queryForList("""
                SELECT a.content_mime_type,a.sha256,a.byte_length,a.request_id,a.step_id
                FROM chat_conversation_asset a JOIN chat_conversation c
                  ON c.id=CAST(a.conversation_id AS UNSIGNED) AND BINARY CAST(c.id AS CHAR)=BINARY a.conversation_id
                  AND c.deleted_at IS NULL AND c.lifecycle_generation=a.conversation_generation
                  AND c.tenant_id=a.tenant_id AND BINARY c.tenant_id=BINARY a.tenant_id
                  AND c.client_id=a.client_id AND BINARY c.client_id=BINARY a.client_id
                  AND c.jiacn=a.owner_jiacn AND BINARY c.jiacn=BINARY a.owner_jiacn
                JOIN chat_interaction_step step ON step.step_id=a.step_id
                  AND BINARY step.step_id=BINARY a.step_id AND step.request_id=a.request_id
                  AND BINARY step.request_id=BINARY a.request_id AND step.tenant_id=a.tenant_id
                  AND BINARY step.tenant_id=BINARY a.tenant_id AND step.owner_jiacn=a.owner_jiacn
                  AND BINARY step.owner_jiacn=BINARY a.owner_jiacn AND step.client_id=a.client_id
                  AND BINARY step.client_id=BINARY a.client_id AND step.conversation_id=a.conversation_id
                  AND BINARY step.conversation_id=BINARY a.conversation_id
                  AND step.conversation_generation=a.conversation_generation
                  AND step.kind='EXECUTE' AND step.state='OUTPUT_COMMITTED'
                WHERE c.task_id=? AND BINARY c.task_id=BINARY ?
                  AND step.task_id=? AND BINARY step.task_id=BINARY ?
                  AND step.target_agent_id=? AND BINARY step.target_agent_id=BINARY ?
                  AND a.tenant_id=? AND BINARY a.tenant_id=BINARY ?
                  AND a.owner_jiacn=? AND BINARY a.owner_jiacn=BINARY ?
                  AND a.client_id=? AND BINARY a.client_id=BINARY ?
                  AND a.conversation_id=? AND BINARY a.conversation_id=BINARY ?
                  AND a.conversation_generation=? AND a.asset_id=? AND BINARY a.asset_id=BINARY ? AND a.revision=?
                """,taskId,taskId,taskId,taskId,targetAgentId,targetAgentId,
                s.tenantId(),s.tenantId(),s.ownerJiacn(),s.ownerJiacn(),s.clientId(),s.clientId(),
                s.conversationId(),s.conversationId(),s.conversationGeneration(),x.assetId(),x.assetId(),Long.parseLong(x.assetRevision()));
        if(rows.size()!=1)throw missing(); Map<String,Object> row=rows.getFirst();
        return source("CURRENT_CONVERSATION_ASSET","CURRENT_CONVERSATION_ASSET",x,
                text(row,"content_mime_type"),text(row,"sha256"),number(row,"byte_length"),
                text(row,"request_id"),text(row,"step_id"));
    }

    private Map<String,Object> source(String sourceKind,String selectorKind,
            ChatTypedDeliberationWire.SourceSelector selector,String mime,String hash,long bytes,
            String parentRequest,String parentStep) {
        if(!mime.matches("[a-z0-9.+-]+/[a-z0-9.+-]+")||!hash.matches("[0-9a-f]{64}")||bytes<0) throw unavailable("Source metadata is invalid");
        Map<String,Object> value=new LinkedHashMap<>(); value.put("sourceKind",sourceKind);
        value.put("selectorKind",selectorKind); value.put("selector",ChatTypedDeliberationWire.selectorMap(selector));
        value.put("mediaType",mediaType(mime)); value.put("contentMimeType",mime); value.put("contentHash",hash);
        value.put("byteLength",Long.toString(bytes)); value.put("parentRequestId",parentRequest); value.put("parentStepId",parentStep);
        return value;
    }
    static String sourceRefId(Scope scope,Map<String,Object> source) {
        Map<String,Object> stableSource=new LinkedHashMap<>(source);
        stableSource.remove("sourceRefId");
        return "source_"+sha256(CanonicalContextJson.write(Map.of(
                "scope",scopeMap(scope),"source",stableSource))).substring(0,40);
    }
    private static Map<String,Object> scopeMap(Scope s){return Map.of("tenantId",s.tenantId(),"ownerJiacn",s.ownerJiacn(),
            "clientId",s.clientId(),"conversationId",s.conversationId(),"conversationGeneration",Long.toString(s.conversationGeneration()));}
    private static String mediaType(String mime){if(mime.startsWith("image/"))return"image";if(mime.startsWith("audio/"))return"audio";if(mime.startsWith("text/"))return"text";return"file";}
    private static String text(Map<String,Object> row,String key){Object v=row.get(key);if(v==null)v=row.get(key.toUpperCase());if(!(v instanceof String s))throw unavailable("Source metadata is invalid");return s;}
    private static long number(Map<String,Object> row,String key){Object v=row.get(key);if(v==null)v=row.get(key.toUpperCase());if(!(v instanceof Number n))throw unavailable("Source metadata is invalid");return n.longValue();}
    private static void exact(String value,int max){if(value==null||value.isBlank()||!value.equals(value.strip())||value.codePointCount(0,value.length())>max||value.codePoints().anyMatch(Character::isISOControl))throw invalid();}
    private static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static ChatDeliberationException invalid(){return new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST,"Typed discussion source selection is invalid");}
    private static ChatDeliberationException missing(){return new ChatDeliberationException(
            ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,"Typed discussion source is unavailable");}
    private static ChatDeliberationException unavailable(String message){return new ChatDeliberationException(ChatDeliberationException.Reason.PERSISTENCE_ERROR,message);}
}
