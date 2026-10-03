package cn.jia.chat.service;

import cn.jia.chat.api.ChatTypedDeliberationWire;
import cn.jia.chat.config.ChatTypedDeliberationSchemaInitializer;
import cn.jia.chat.handler.TypedDeliberationSessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatTypedDeliberationContextServiceTest {
    private final ChatActionCapabilityService capabilities=mock(ChatActionCapabilityService.class);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final TypedDeliberationSessionRegistry sessions=new TypedDeliberationSessionRegistry();
    private final ChatTypedDeliberationSchemaInitializer schema=mock(ChatTypedDeliberationSchemaInitializer.class);
    private final ChatTypedDeliberationContextService.Scope scope=
            new ChatTypedDeliberationContextService.Scope("0","owner","client","42",1);

    @Test void noneUsesLiveDeclarationAndNeverReadsBytes() {
        ready();var context=service().resolve(scope,"task","agent",List.of());
        assertEquals(3,context.facts().get("schemaVersion"));assertEquals(List.of(),context.facts().get("availableActions"));assertFalse(context.facts().containsKey("supportedOperations"));assertEquals(List.of(),context.facts().get("availableSources"));
        verifyNoInteractions(jdbc);
    }
    @Test void workspaceAndConversationAssetProjectOnlyAuthoritativeMetadata() {
        ready();when(jdbc.queryForList(contains("agent_personal_workspace_task_file_link"),any(Object[].class)))
                .thenReturn(List.of(Map.of("content_mime_type","image/jpeg","content_hash","a".repeat(64),"byte_length",12L)));
        when(jdbc.queryForList(contains("chat_conversation_asset"),any(Object[].class)))
                .thenReturn(List.of(Map.of("content_mime_type","audio/wav","sha256","b".repeat(64),"byte_length",13L,"request_id","r0","step_id","s0")));
        var selectors=List.of(new ChatTypedDeliberationWire.SourceSelector("TASK_LINKED_WORKSPACE_VERSION","file","2","REFERENCE",null,null),
                new ChatTypedDeliberationWire.SourceSelector("CURRENT_CONVERSATION_ASSET",null,null,null,"asset","3"));
        var context=service().resolve(scope,"task","agent",selectors);
        var facts=ChatActionOutcomeContract.facts(context.facts());
        assertEquals(3,facts.schemaVersion());assertEquals(2,facts.availableSources().size());
        assertEquals(java.util.Set.of("image","audio"),facts.availableSources().stream()
                .map(ChatActionOutcomeContract.Source::mediaType).collect(java.util.stream.Collectors.toSet()));
        assertTrue(context.sourceCatalogJson().contains("\"contentHash\":\""+"a".repeat(64)+"\""));
        assertTrue(context.sourceCatalogJson().contains("\"parentRequestId\":\"r0\""));
        assertFalse(context.sourceCatalogJson().contains("path"));assertFalse(context.sourceCatalogJson().contains("url"));
    }
    @Test void missingScopedSourceIsOpaque404AndDuplicateSelectionsAreInvalid() {
        ready();
        var selector=new ChatTypedDeliberationWire.SourceSelector(
                "TASK_LINKED_WORKSPACE_VERSION","file","2","REFERENCE",null,null);
        var missing=assertThrows(ChatDeliberationException.class,
                ()->service().resolve(scope,"task","agent",List.of(selector)));
        assertEquals(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,missing.reason());
        when(jdbc.queryForList(contains("agent_personal_workspace_task_file_link"),any(Object[].class)))
                .thenReturn(List.of(Map.of("content_mime_type","image/png",
                        "content_hash","a".repeat(64),"byte_length",12L)));
        var duplicate=assertThrows(ChatDeliberationException.class,
                ()->service().resolve(scope,"task","agent",List.of(selector,selector)));
        assertEquals(ChatDeliberationException.Reason.INVALID_REQUEST,duplicate.reason());
    }
    @Test void absentAmbiguousUnsupportedOrUnreadySchemaAreUnavailable() {
        when(schema.ready()).thenReturn(true);
        var failure=assertThrows(ChatDeliberationException.class,()->service().resolve(scope,"task","agent",List.of()));
        assertEquals(ChatDeliberationException.Reason.PERSISTENCE_ERROR,failure.reason());
        sessions.register("s1","0","owner","client","agent",declaration());
        sessions.register("s2","0","owner","client","agent",declaration());
        assertEquals(ChatDeliberationException.Reason.PERSISTENCE_ERROR,assertThrows(ChatDeliberationException.class,()->service().resolve(scope,"task","agent",List.of())).reason());
        when(schema.ready()).thenReturn(false);
        assertEquals(ChatDeliberationException.Reason.PERSISTENCE_ERROR,assertThrows(ChatDeliberationException.class,()->service().resolve(scope,"task","agent",List.of())).reason());
    }
    @Test void full32MixedInputCatalogueUsesExactRoleAndOwnerPredicates() {
        ready();
        when(jdbc.queryForList(contains("agent_personal_workspace_task_file_link"),any(Object[].class)))
                .thenAnswer(inv->{
                    String sql=inv.getArgument(0);
                    assertTrue(sql.contains("l.link_role=? AND BINARY l.link_role=BINARY ?"));
                    assertTrue(sql.contains("l.owner_jiacn=? AND BINARY l.owner_jiacn=BINARY ?"));
                    Object[] args=java.util.Arrays.copyOfRange(inv.getArguments(),1,inv.getArguments().length);
                    assertEquals("INPUT",args[args.length-1]);assertEquals("INPUT",args[args.length-2]);
                    return List.of(Map.of("content_mime_type","application/pdf","content_hash","a".repeat(64),"byte_length",12L));
                });
        var selectors=new java.util.ArrayList<ChatTypedDeliberationWire.SourceSelector>();
        for(int i=0;i<32;i++)selectors.add(new ChatTypedDeliberationWire.SourceSelector(
                "TASK_LINKED_WORKSPACE_VERSION","file-"+i,"1","INPUT",null,null));
        var result=service().resolve(scope,"task","agent",selectors);
        assertEquals(32,result.selectors().size());
        assertEquals(32,((List<?>)result.facts().get("availableSources")).size());
        assertEquals(32,ChatTypedDeliberationContextService.parseCatalog(result.sourceCatalogJson()).size());
        assertFalse(result.sourceCatalogJson().contains("storageUri"));
        selectors.add(new ChatTypedDeliberationWire.SourceSelector("TASK_LINKED_WORKSPACE_VERSION","excess","1","INPUT",null,null));
        assertThrows(ChatDeliberationException.class,()->service().resolve(scope,"task","agent",selectors));
    }

    @Test void serverConstructedInvalidRoleIsRejectedBeforeAnyCatalogueQuery() {
        ready();
        var invalid=new ChatTypedDeliberationWire.SourceSelector("TASK_LINKED_WORKSPACE_VERSION","file","1","OUTPUT",null,null);
        assertThrows(ChatDeliberationException.class,()->service().resolve(scope,"task","agent",List.of(invalid)));
        verifyNoInteractions(jdbc);
    }

    @Test void newContextCarriesV3LiveActionsWithoutFabricatingReadFacts() {
        ready();
        var action=Map.<String,Object>of("actionId","generate-image","kind","EXECUTE","operation","GENERATE_IMAGE",
                "inputMediaTypes",List.of("image"),"minSources",0,"maxSources",16);
        when(capabilities.available(any(),anyList())).thenReturn(List.of(action));
        var context=service().resolve(scope,"task","agent",List.of());
        var facts=ChatActionOutcomeContract.facts(context.facts());
        assertEquals(3,facts.schemaVersion());assertEquals("generate-image",facts.availableActions().getFirst().actionId());
        assertEquals(List.of(),facts.inspectedSourceRefIds());
        verify(capabilities).available(new ChatActionCapabilityService.Scope("0","owner","client","agent"),List.of());
        verifyNoInteractions(jdbc);
    }

    private void ready(){when(schema.ready()).thenReturn(true);sessions.register("s1","0","owner","client","agent",declaration());}
    private static Map<String,Object> declaration(){return Map.of("schemaVersion",3,"state","READY","carrier","CHAT_MESSAGE_FINAL_SIDECAR_V3","referenceModes",List.of("NONE","AVAILABLE"),"outcomeKinds",List.of("ANSWER","CLARIFY","ACTION_REQUEST"),"engine","CODEX_APP_SERVER_NATIVE_OUTPUT_SCHEMA","strictNoToolsVerified",false,"toolPolicy","read-only-constrained");}
    private ChatTypedDeliberationContextService service(){return new ChatTypedDeliberationContextService(jdbc,sessions,schema,capabilities,true);}
}
