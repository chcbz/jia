package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.*;
import cn.jia.chat.api.ChatTypedDeliberationWire;
import cn.jia.chat.config.ChatTypedDeliberationSchemaInitializer;
import cn.jia.chat.dao.*;
import cn.jia.chat.deliberation.*;
import cn.jia.chat.entity.*;
import cn.jia.chat.handler.TypedDeliberationSessionRegistry;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual discussion/context/registration/package preparation and real Spring/H2 txn; persistence doubles. */
class ChatDiscussionV2TransactionTest {
    @Configuration @EnableTransactionManagement(proxyTargetClass=true) static class TxConfig { }
    @TempDir Path packageRoot;
    @Test void defaultSourcesEnterOnlyOriginalChatWithActualNativeDeclarationAndKeyReplayDoesNotUpgradeAgain() throws Exception {
        try(var f=new Fixture(packageRoot)) {
            var command=command("TASK_DEFAULT",List.of());var accepted=f.service.admit("0",f.sender,"42","key",command);
            assertEquals(2,accepted.schemaVersion());assertEquals(List.of("turn"),accepted.turnIds());assertFalse(accepted.replay());
            assertEquals(List.of("prepare","root","baseline","facts","chat","admission"),f.order);
            var nativeFacts=f.nativeFacts.get();assertEquals(1,ChatMaterialsInputContract.refs(nativeFacts).size());assertTrue(nativeFacts.toString().contains("LOCAL_IMAGE"));
            var catalog=ChatTypedDeliberationContextService.parseCatalog(f.prior.get().sourceCatalogJson());assertEquals(2,catalog.size());
            assertTrue(catalog.stream().allMatch(s->s.get("parentRequestId")==null&&s.get("parentStepId")==null));
            assertTrue(catalog.stream().anyMatch(s->"application/pdf".equals(s.get("contentMimeType"))));
            verify(f.deliberation,times(1)).admit(anyString(),eq(f.sender),eq("42"),eq(1L),any(),eq(InteractionRoute.CHAT),any(),isNull(),any(),any());
            clearInvocations(f.grants,f.capabilities,f.sourceJdbc,f.preparation);f.sessions.remove("session");
            var replay=f.service.admit("0",f.sender,"42","key",command);assertTrue(replay.replay());assertEquals(accepted.requestId(),replay.requestId());
            verifyNoInteractions(f.grants,f.capabilities,f.sourceJdbc,f.preparation);assertEquals(3,f.evidence.queryForObject("SELECT COUNT(*) FROM writes",Integer.class));
        }
    }
    @Test void noneAndExplicitDoNotSilentlyInheritTaskDirectory() throws Exception {
        for(String mode:List.of("NONE","EXPLICIT"))try(var f=new Fixture(packageRoot.resolve(mode))) {
            var selectors="NONE".equals(mode)?List.<ChatTypedDeliberationWire.SourceSelector>of():List.of(new ChatTypedDeliberationWire.SourceSelector("TASK_LINKED_WORKSPACE_VERSION","pdf","1","INPUT",null,null));
            f.service.admit("0",f.sender,"42",mode,command(mode,selectors));assertNull(f.nativeFacts.get());
            assertEquals(selectors.size(),ChatTypedDeliberationContextService.parseCatalog(f.prior.get().sourceCatalogJson()).size());
            verify(f.sourceJdbc,never()).queryForList(contains("SELECT l.file_id"),any(Object[].class));
        }
    }
    @Test void baselineAndChatRequestAndAdmissionRollBackTogetherOnAnyPersistenceFailure() throws Exception {
        for(String stage:List.of("baseline","chat","admission"))try(var f=new Fixture(packageRoot.resolve(stage))) {
            f.fail=stage;var error=assertThrows(RuntimeException.class,()->f.service.admit("0",f.sender,"42",stage,command("TASK_DEFAULT",List.of())));
            assertEquals("injected-"+stage,error.getMessage());assertEquals(0,f.evidence.queryForObject("SELECT COUNT(*) FROM writes",Integer.class));
        }
    }
    @Test void locatorIsNotAuthorityAndAssignmentDriftBetweenPreparationAndRootPreventsAnyGrantOrChat() throws Exception {
        try(var f=new Fixture(packageRoot)) {
            when(f.bindings.lock(any(),eq("task"))).thenReturn(new ChatBountyBindingStore.Binding(4,42L));
            assertThrows(ChatDeliberationException.class,()->f.service.admit("0",f.sender,"42","key",command("TASK_DEFAULT",List.of())));
            verifyNoInteractions(f.grants,f.capabilities,f.deliberation);assertEquals(0,f.evidence.queryForObject("SELECT COUNT(*) FROM writes",Integer.class));
        }
    }
    static ChatTypedDeliberationWire.DiscussionCommand command(String mode,List<ChatTypedDeliberationWire.SourceSelector> refs) {
        return new ChatTypedDeliberationWire.DiscussionCommand("DISCUSSION","task",3,"直接看上传图",null,null,null,null,refs,mode);
    }
    static class Fixture implements AutoCloseable {
        final ServerResolvedSender sender=new ServerResolvedSender("user","Human","owner","client",DisplayNameSource.JIACN);
        final List<String> order=new ArrayList<>();String fail;
        final AgentTaskMutationTransaction roots=mock(AgentTaskMutationTransaction.class);final AgentTaskExecutionGrantService grants=mock(AgentTaskExecutionGrantService.class);
        final ChatBountyBindingStore bindings=mock(ChatBountyBindingStore.class);final ChatConversationDao conversations=mock(ChatConversationDao.class);final JuyitingConversationScopeService scopes=mock(JuyitingConversationScopeService.class);
        final ChatDeliberationService deliberation=mock(ChatDeliberationService.class);final ChatDeliberationDao events=mock(ChatDeliberationDao.class);final ChatTypedDeliberationService typed=mock(ChatTypedDeliberationService.class);final ChatTypedDeliberationStore store=mock(ChatTypedDeliberationStore.class);
        final JdbcTemplate sourceJdbc=mock(JdbcTemplate.class);final ChatActionCapabilityService capabilities=mock(ChatActionCapabilityService.class);
        final TypedDeliberationSessionRegistry sessions=new TypedDeliberationSessionRegistry();
        final AtomicReference<ChatTypedDeliberationStore.Admission> prior=new AtomicReference<>();final AtomicReference<Map<String,Object>> nativeFacts=new AtomicReference<>();
        final AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext();final JdbcTemplate evidence;final ChatSkillPackagePreparationService preparation;final ChatTypedDiscussionAdmissionService service;
        Fixture(Path path) throws Exception {
            java.nio.file.Files.createDirectories(path);var packages=new ChatSkillColdPreparationTransactionTest();packages.packageRoot=path;packages.fixture();
            var source=new DriverManagerDataSource("jdbc:h2:mem:discussion_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");evidence=new JdbcTemplate(source);evidence.execute("CREATE TABLE writes(label VARCHAR(20) PRIMARY KEY)");
            var manager=new DataSourceTransactionManager(source);var tx=new TransactionTemplate(manager);
            preparation=spy(new ChatSkillPackagePreparationService(packages.installed));doAnswer(i->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());order.add("prepare");i.callRealMethod();return null;}).when(preparation).prepare(any());
            when(roots.executeWithLockedTaskRootInOwnerScope(anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(i->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());order.add("root");return tx.execute(status->((AgentTaskMutationTransaction.LockedTaskMutation<?>)i.getArgument(4)).apply(new AgentTaskMetaEntity().setTaskVersion(3L).setAssignedAgentId("agent")));});
            when(bindings.lock(any(),eq("task"))).thenReturn(new ChatBountyBindingStore.Binding(3,42L));
            var conversation=new ChatConversationEntity().setId(42L).setJiacn("owner").setConversationType("juyiting").setConversationScopeType("bounty").setConversationScopeKey("task:task").setTaskId("task").setTargetAgentIds("[\"agent\"]").setLifecycleGeneration(1L);conversation.setTenantId("0");conversation.setClientId("client");
            when(conversations.findScopedById("owner","client","42")).thenReturn(conversation);when(conversations.lockScopedById("owner","client","42")).thenReturn(conversation);when(scopes.parsePersistedTargetAgentIds("[\"agent\"]")).thenReturn(List.of("agent"));when(typed.store()).thenReturn(store);
            when(store.findAdmissionByKey(any(),anyString(),anyBoolean())).thenAnswer(i->prior.get());
            when(grants.resolveSkillBaseline(any(),eq("task"),eq(3L),eq("agent"))).thenAnswer(i->{write("baseline");return new AgentTaskExecutionGrantService.Admission("grant",2,3,"agent","EXECUTE_SKILL",false,List.of(),null,null);});
            var declaration=new LinkedHashMap<String,Object>();declaration.put("schemaVersion",4);declaration.put("state","READY");declaration.put("carrier","CHAT_MESSAGE_FINAL_SIDECAR_V4");declaration.put("referenceModes",List.of("NONE","AVAILABLE"));declaration.put("outcomeKinds",List.of("ANSWER","CLARIFY","ACTION_REQUEST"));declaration.put("engine","CODEX_APP_SERVER_NATIVE_OUTPUT_SCHEMA");declaration.put("strictNoToolsVerified",false);declaration.put("toolPolicy","read-only-constrained");
            declaration.put("materialsInput",Map.of("schemaVersion",1,"supportedInputs",List.of(Map.of("mediaKind","image","mimeType","image/png","carrier","LOCAL_IMAGE","carrierContractDigest","sha256:"+"a".repeat(64)))));sessions.register("session","0","owner","client","agent",declaration);
            when(sourceJdbc.queryForList(anyString(),any(Object[].class))).thenAnswer(i->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());String sql=i.getArgument(0);
                if(sql.contains("SELECT l.file_id"))return List.of(Map.of("file_id","png","file_version",1,"link_role","INPUT"),Map.of("file_id","pdf","file_version",1,"link_role","INPUT"));
                String file=(String)i.getArgument(9);return List.of(Map.of("content_mime_type","png".equals(file)?"image/png":"application/pdf","content_hash","b".repeat(64),"byte_length",4));});
            when(capabilities.available(any(),eq("task"),anyList())).thenAnswer(i->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertTrue(order.contains("baseline"));order.add("facts");assertTrue(packages.installed.read(packages.scope).skills().getFirst().verified());return List.of();});
            var schema=mock(ChatTypedDeliberationSchemaInitializer.class);when(schema.ready()).thenReturn(true);var contexts=new ChatTypedDeliberationContextService(sourceJdbc,sessions,schema,capabilities,true);
            when(deliberation.admit(anyString(),eq(sender),eq("42"),eq(1L),any(),eq(InteractionRoute.CHAT),any(),isNull(),any(),any())).thenAnswer(i->{write("chat");ChatMessageDTO input=i.getArgument(6);Map<String,Object> metadata=i.getArgument(9);
                nativeFacts.set(ChatMaterialsInputContract.bind(metadata.get("nativeMaterials"),"0","owner","client","42",1,"task",input.getRequestId(),"turn","agent"));return new ChatDeliberationService.Admission(input.getRequestId(),1,"8","42",1,InteractionRoute.CHAT,List.of(new ChatDeliberationService.Dispatch(input.getRequestId(),"turn","dispatch","event","agent","CHAT","RECEIVED","snapshot","sha256:"+"c".repeat(64),Map.of(),Map.of())),false);});
            when(store.insertAdmission(any())).thenAnswer(i->{write("admission");prior.set(i.getArgument(0));return 1;});
            context.register(TxConfig.class);context.registerBean("transactionManager",DataSourceTransactionManager.class,()->manager);context.registerBean(AgentTaskExecutionGrantService.class,()->grants);context.registerBean(ChatSkillPackagePreparationService.class,()->preparation);
            context.registerBean(ChatTypedDiscussionAdmissionService.class,()->new ChatTypedDiscussionAdmissionService(roots,bindings,conversations,scopes,deliberation,events,contexts,typed,mock(ChatActionFinalService.class)));context.refresh();service=context.getBean(ChatTypedDiscussionAdmissionService.class);
        }
        void write(String label){assertTrue(TransactionSynchronizationManager.isActualTransactionActive());order.add(label);evidence.update("INSERT INTO writes VALUES(?)",label);if(label.equals(fail))throw new IllegalStateException("injected-"+label);}
        public void close(){context.close();}
    }
}
