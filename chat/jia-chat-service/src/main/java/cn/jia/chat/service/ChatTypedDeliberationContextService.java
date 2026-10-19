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
    private ChatSkillSourceAccessService skillSources;
    @org.springframework.beans.factory.annotation.Autowired
    public void setSkillSources(ChatSkillSourceAccessService value) {skillSources=Objects.requireNonNull(value);}

    /** TASK_DEFAULT is only the active task-linked directory, never all workspace/history. */
    public List<ChatTypedDeliberationWire.SourceSelector> effectiveSelectors(Scope s,String taskId,String materialScope,
            List<ChatTypedDeliberationWire.SourceSelector> explicit,List<ChatTypedDeliberationWire.SourceSelector> inherited) {
        if(!Set.of("TASK_DEFAULT","EXPLICIT","NONE").contains(materialScope)||explicit==null||inherited==null)throw invalid();
        if("NONE".equals(materialScope)) {if(!explicit.isEmpty())throw invalid();return List.of();}
        var selected=new java.util.LinkedHashSet<ChatTypedDeliberationWire.SourceSelector>();
        if("TASK_DEFAULT".equals(materialScope)) {
            var rows=jdbc.queryForList("""
                SELECT l.file_id,l.file_version,l.link_role FROM agent_personal_workspace_task_file_link l
                JOIN agent_personal_workspace_file f ON BINARY f.tenant_id=BINARY l.tenant_id
                  AND BINARY f.client_id=BINARY l.client_id AND BINARY f.owner_jiacn=BINARY l.owner_jiacn
                  AND BINARY f.file_id=BINARY l.file_id AND f.state='ACTIVE'
                WHERE BINARY l.tenant_id=BINARY ? AND BINARY l.owner_jiacn=BINARY ? AND BINARY l.client_id=BINARY ?
                  AND BINARY l.task_id=BINARY ? AND l.link_state='ACTIVE' AND l.link_role IN ('INPUT','REFERENCE')
                ORDER BY BINARY l.file_id,l.file_version,BINARY l.link_role
                """,s.tenantId(),s.ownerJiacn(),s.clientId(),taskId);
            for(var row:rows)selected.add(new ChatTypedDeliberationWire.SourceSelector("TASK_LINKED_WORKSPACE_VERSION",
                    text(row,"file_id"),Long.toString(number(row,"file_version")),text(row,"link_role"),null,null));
            selected.addAll(inherited);
        }
        selected.addAll(explicit);
        if(selected.size()>32)throw new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST,
                "More than 32 effective sources; explicitly select this message's sources");
        return List.copyOf(selected);
    }


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
                    ? workspace(scope,taskId,selector) : "CURRENT_CONVERSATION_ASSET".equals(selector.kind())
                            ? asset(scope,taskId,targetAgentId,selector) : result(scope,taskId,selector);
            String sourceRef=sourceRefId(scope,entry);
            if (!sourceRefs.add(sourceRef)) throw invalid();
            Map<String,Object> stored=new LinkedHashMap<>(entry); stored.put("sourceRefId",sourceRef);
            catalog.add(Collections.unmodifiableMap(new LinkedHashMap<>(stored)));
            available.add(Map.of("sourceRefId",sourceRef,"kind",entry.get("sourceKind"),
                    "mediaType",entry.get("mediaType"),"contentMimeType",entry.get("contentMimeType")));
            normalized.add(selector);
        }
        catalog.sort(Comparator.comparing(value -> (String)value.get("sourceRefId")));
        available.sort(Comparator.comparing(value -> (String)value.get("sourceRefId")));
        Map<String,Object> facts=new LinkedHashMap<>(); facts.put("schemaVersion",5);
        facts.put("availableSources",List.copyOf(available));
        facts.put("inspectedSourceRefIds",List.of());
        facts.put("availableActions",capabilities.available(new ChatActionCapabilityService.Scope(
                scope.tenantId(),scope.ownerJiacn(),scope.clientId(),targetAgentId),taskId,List.copyOf(catalog)));
        ChatActionOutcomeContract.facts(facts);
        return new Context(Map.copyOf(facts),CanonicalContextJson.write(catalog),normalized,ready.frozenDeclaration());
    }

    Context withFormalRevisionSources(Scope scope,String task,String target,Context context,List<Map<String,Object>> formal) {
        var catalog=new ArrayList<Map<String,Object>>(parseCatalog(context.sourceCatalogJson()));
        catalog.addAll(formal);catalog.sort(Comparator.comparing(x->(String)x.get("sourceRefId")));
        if(catalog.stream().map(x->x.get("sourceRefId")).distinct().count()!=catalog.size())throw invalid();
        var facts=new LinkedHashMap<String,Object>(context.facts());
        facts.put("availableSources",catalog.stream().map(x->Map.of("sourceRefId",x.get("sourceRefId"),
                "kind",x.get("sourceKind"),"mediaType",x.get("mediaType"),"contentMimeType",x.get("contentMimeType"))).toList());
        facts.put("availableActions",capabilities.available(new ChatActionCapabilityService.Scope(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),target),task,catalog));
        ChatActionOutcomeContract.facts(facts);
        return new Context(facts,CanonicalContextJson.write(catalog),context.selectors(),context.frozenDeclaration());
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
        if(skillSources==null)throw unavailable("Skill source adapter is unavailable");
        var owner=new cn.jia.agent.service.PersonalWorkspaceExecutionService.OwnerScope(s.tenantId(),s.clientId(),s.ownerJiacn());
        skillSources.conversation(owner,taskId,s.conversationId(),s.conversationGeneration());
        var actual=skillSources.asset(owner,taskId,s.conversationId(),s.conversationGeneration(),targetAgentId,
                ChatTypedDeliberationWire.selectorMap(x));
        var value=source("CURRENT_CONVERSATION_ASSET",x.kind(),x,actual.contentMimeType(),actual.sha256(),actual.byteLength(),actual.requestId(),actual.stepId());
        value.put("sourceIdentity",ChatSkillSourceAccessService.assetIdentity(actual));
        return value;
    }
    private Map<String,Object> result(Scope s,String task,ChatTypedDeliberationWire.SourceSelector x) {
        if(skillSources==null)throw unavailable("Skill source adapter is unavailable");
        var owner=new cn.jia.agent.service.PersonalWorkspaceExecutionService.OwnerScope(s.tenantId(),s.clientId(),s.ownerJiacn());
        skillSources.conversation(owner,task,s.conversationId(),s.conversationGeneration());
        var identity=ChatTypedDeliberationWire.selectorMap(x);
        byte[] bytes=skillSources.result(owner,task,s.conversationId(),s.conversationGeneration(),identity);
        var value=source("EXECUTION_RESULT",x.kind(),x,"application/json",ChatSkillSourceAccessService.sha(bytes),bytes.length,null,null);
        value.put("sourceIdentity",identity);return value;
    }

    private Map<String,Object> source(String sourceKind,String selectorKind,
            ChatTypedDeliberationWire.SourceSelector selector,String mime,String hash,long bytes,
            String parentRequest,String parentStep) {
        if(!mime.matches("[a-z0-9.+-]+/[a-z0-9.+-]+")||!hash.matches("[0-9a-f]{64}")||bytes<0) throw unavailable("Source metadata is invalid");
        Map<String,Object> value=new LinkedHashMap<>(); value.put("sourceKind",sourceKind);
        value.put("selectorKind",selectorKind); value.put("selector",ChatTypedDeliberationWire.selectorMap(selector));
        value.put("sourceIdentity",ChatTypedDeliberationWire.selectorMap(selector));
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
