package cn.jia.chat.service;

import cn.jia.agent.service.*;
import cn.jia.chat.dao.*;
import cn.jia.chat.deliberation.*;
import cn.jia.chat.entity.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real cold SkillPackages and Spring/H2 transactions; scoped persistence doubles, not MySQL validation. */
class ChatSkillDispatchTransactionTest {
    @Configuration @EnableTransactionManagement(proxyTargetClass=true) static class TxConfig { }
    @TempDir Path packageRoot;
    @Test void coldDispatchPreparesBeforeRootThenCreateChildLinkAndSettleAreAtomicAndReplaySkipsLiveRead() throws Exception {
        for(String failure:List.of("create","request","step","link","admission","event","settle","none","external-task")) {
            var folder=Files.createDirectory(packageRoot.resolve(failure));var packageFixture=new ChatSkillColdPreparationTransactionTest();
            packageFixture.packageRoot=folder;packageFixture.fixture();
            var approved=packageFixture.market.selectApprovedProductVersion("0","client","pv");
            String manifestJson;try(var zip=new ZipFile(folder.resolve(HexFormat.of().formatHex(approved.getPackageSha256())+".zip").toFile())) {
                manifestJson=new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            }
            String packageHash=HexFormat.of().formatHex(approved.getPackageSha256()),manifestHash=ChatSkillSourceAccessService.sha(manifestJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String taskOwner="external-task".equals(failure)?"customer-owner":"owner";
            var scope=new ChatTypedDeliberationStore.Scope("0",taskOwner,"client","1",1);
            var taskKey=new SkillCapabilityLookup.Scope("0","client",taskOwner,"agent");
            var binding=new LinkedHashMap<String,Object>();binding.putAll(Map.of("tenantId","0","ownerJiacn",taskOwner,"clientId","client","conversationId","1","conversationGeneration","1","requestId","parent","requestRevision","1","turnId","turn","dispatchId","dispatch","snapshotId","snapshot"));
            binding.put("contextDigest","sha256:"+"c".repeat(64));binding.put("targetAgentId","agent");binding.put("route","CHAT");binding.put("taskId","task");
            var facts=new ChatActionOutcomeContract.Facts(4,List.of(new ChatActionOutcomeContract.Capability("act_analysis","EXECUTE","EXECUTE_SKILL",new ChatActionOutcomeContract.Skill("analysis","1.0.0",packageHash,manifestHash),Map.of("$schema","https://json-schema.org/draft/2020-12/schema","type","object"),List.of(),0,0,List.of())),List.of(),List.of());
            var outcome=new ChatActionOutcomeContract.Outcome(4,"ACTION_REQUEST","分析",null,new ChatActionOutcomeContract.Action("act_analysis","分析当前指令",List.of(),Map.of()));
            var valid=ChatActionFinalValidator.validateJson(binding,ChatActionFinalValidator.factsMap(facts),null,"分析",4,ChatSkillSnapshotJson.write(ChatActionFinalValidator.outcomeMap(outcome)),null);
            var parent=new ChatTypedDeliberationStore.Outcome("outcome",scope,"parent",1,"turn","task",3,11,valid.finalDigest(),"ACTION_REQUEST","分析",ChatSkillSnapshotJson.write(binding),"{}","{}","[]",1);
            var priorParent=new ChatTypedDeliberationStore.Admission("parent_admit",scope,"parent_key","parent_digest","parent_digest","DISCUSSION","task",3,null,null,"parent",1,10,"[]","[]","ADMITTED",0,0,1);
            var action=new ChatActionFinalService.BoundAction(parent,priorParent,valid);
            var source=new DriverManagerDataSource("jdbc:h2:mem:dispatch_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
            var manager=new DataSourceTransactionManager(source);var tx=new TransactionTemplate(manager);var evidence=new JdbcTemplate(source);evidence.execute("CREATE TABLE writes(label VARCHAR(20) PRIMARY KEY)");
            java.util.function.Function<String,Integer> write=label->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());evidence.update("INSERT INTO writes VALUES(?)",label);if(label.equals(failure))throw new IllegalStateException("injected-"+label);return 1;};
            var requests=mock(ChatDeliberationService.class);var dao=mock(ChatDeliberationDao.class);var typed=mock(ChatTypedDeliberationStore.class);var steps=mock(ChatInteractionStepStore.class);var grants=mock(AgentTaskExecutionGrantService.class);var skills=mock(SkillExecutionService.class);var runtime=mock(SkillCapabilityLookup.class);var policy=mock(TaskSkillPolicyService.class);
            var fence=new AgentManagedSessionLookup.SessionFence("runtime-install","host","runtime",7);
            when(policy.read(taskKey,"task")).thenReturn(new TaskSkillPolicyService.Snapshot(true,null,3,fence,packageFixture.scope));
            when(runtime.lookup(packageFixture.scope,fence)).thenReturn(new SkillCapabilityLookup.View(SkillCapabilityLookup.State.AVAILABLE,"session",fence,new SkillCapabilityLookup.Catalog("1",List.of(new SkillCapabilityLookup.RuntimeSkill("analysis","1.0.0",packageHash,manifestHash,"native-model",SkillCapabilityLookup.Readiness.READY,null,"d".repeat(64),null)))));
            when(grants.resolveAndAdmit(any(),eq("task"),eq(3L),eq("agent"),eq("EXECUTE_SKILL"),eq(false))).thenReturn(new AgentTaskExecutionGrantService.Admission("grant",2,3,"agent","EXECUTE_SKILL",false,List.of(),null,null));
            var user=new ChatMessageEntity().setId(10L).setContent("分析当前指令");when(requests.requireActionUser(action)).thenReturn(user);
            var child=new AtomicReference<ChatRequestEntity>();var admission=new AtomicReference<ChatTypedDeliberationStore.Admission>();var step=new AtomicReference<ChatInteractionStepStore.Step>();var link=new AtomicReference<ChatInteractionStepStore.ExecutionLink>();
            when(dao.findRequest(anyString(),anyString(),anyString(),anyString())).thenAnswer(i->child.get());
            when(typed.findAdmissionByKey(eq(scope),anyString(),anyBoolean())).thenAnswer(i->admission.get());
            when(steps.findStep(anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong())).thenAnswer(i->step.get());
            when(steps.findLink(anyString(),anyString(),anyString(),anyString())).thenAnswer(i->link.get());
            when(dao.insertRequest(any())).thenAnswer(i->{ChatRequestEntity row=i.getArgument(0);row.setId(20L);child.set(row);return write.apply("request");});
            when(steps.insertStep(any())).thenAnswer(i->{step.set(i.getArgument(0));return write.apply("step");});
            when(steps.insertLink(any())).thenAnswer(i->{link.set(i.getArgument(0));return write.apply("link");});
            when(typed.insertAdmission(any())).thenAnswer(i->{admission.set(i.getArgument(0));return write.apply("admission");});
            doAnswer(i->{write.apply("event");return null;}).when(requests).persistActionStarted(eq(action),anyString(),eq("EXECUTE"),anyLong());
            when(skills.create(any(),any())).thenAnswer(i->{var command=(SkillExecutionService.Create)i.getArgument(1);
                assertEquals(taskOwner,((PersonalWorkspaceExecutionService.OwnerScope)i.getArgument(0)).ownerJiacn());assertTrue(packageFixture.installed.read(packageFixture.scope).skills().getFirst().verified());write.apply("create");return new SkillExecutionService.Creation("exec","task","run","1","agent","QUEUED",ChatSkillSnapshotJson.sha(command.snapshot()));});
            var service=new ChatActionExecutionService(requests,dao,typed,steps,grants,skills,packageFixture.installed,runtime,policy);
            var finals=mock(ChatActionFinalService.class);var roots=mock(AgentTaskMutationTransaction.class);var bindings=mock(ChatBountyBindingStore.class);var conversations=mock(ChatConversationDao.class);var scopes=mock(JuyitingConversationScopeService.class);var outbox=mock(ChatDeliberationOutboxService.class);
            when(roots.executeWithLockedTaskRootInOwnerScope(anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(i->tx.execute(status->((AgentTaskMutationTransaction.LockedTaskMutation<?>)i.getArgument(4)).apply(new cn.jia.agent.entity.AgentTaskMetaEntity().setTaskVersion(3L).setAssignedAgentId("agent"))));
            when(bindings.lock(any(),eq("task"))).thenAnswer(i->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());return new ChatBountyBindingStore.Binding(3,1L);});
            var conversation=new ChatConversationEntity().setId(1L).setJiacn(taskOwner).setLifecycleGeneration(1L).setConversationType("juyiting").setConversationScopeType("bounty").setConversationScopeKey("task:task").setTaskId("task").setTargetAgentIds("agent");conversation.setTenantId("0");conversation.setClientId("client");when(conversations.lockScopedById(taskOwner,"client","1")).thenReturn(conversation);when(scopes.parsePersistedTargetAgentIds("agent")).thenReturn(List.of("agent"));
            var row=new ChatDispatchOutboxEntity();var claim=new ChatDeliberationOutboxService.Claim(row,false);when(finals.loadAction(row)).thenReturn(action);when(outbox.requireActiveClaim(eq(claim),anyLong())).thenReturn(row);when(outbox.sent(eq(claim),anyLong())).thenAnswer(i->{write.apply("settle");return true;});
            try(var context=new AnnotationConfigApplicationContext()) {
                context.register(TxConfig.class);context.registerBean("transactionManager",DataSourceTransactionManager.class,()->manager);context.registerBean(ChatActionExecutionService.class,()->service);context.registerBean(ChatActionDispatchService.class,()->new ChatActionDispatchService(finals,roots,bindings,conversations,scopes,typed,mock(ChatTypedInspectionContextService.class),requests,dao,outbox,context.getBeanProvider(ChatActionDispatchService.Executor.class)));context.refresh();
                var executor=context.getBean(ChatActionExecutionService.class);var dispatch=context.getBean(ChatActionDispatchService.class);
                assertThrows(IllegalTransactionStateException.class,()->executor.admit(action));
                if(!Set.of("none","external-task").contains(failure)) {var error=assertThrows(RuntimeException.class,()->dispatch.consume(claim));if(!("injected-"+failure).equals(error.getMessage()))throw new AssertionError("Unexpected rejection before "+failure,error);assertEquals(0,evidence.queryForObject("SELECT COUNT(*) FROM writes",Integer.class));}
                else {
                    dispatch.consume(claim);assertEquals(7,evidence.queryForObject("SELECT COUNT(*) FROM writes",Integer.class));
                    assertEquals(taskOwner,child.get().getOwnerJiacn());assertEquals("exec",link.get().executionId());assertEquals("EXECUTE",step.get().kind());assertEquals(10L,child.get().getUserMessageId());
                    // Original key must replay after revoke/removal without touching installed/policy/grant/core again.
                    clearInvocations(packageFixture.market,packageFixture.app,grants,skills,runtime,policy,requests);
                    packageFixture.entitlement.setStatus("REVOKED");try(var files=Files.list(folder)){for(var path:files.toList())Files.delete(path);}
                    when(outbox.sent(eq(claim),anyLong())).thenReturn(true);dispatch.consume(claim);
                    verifyNoInteractions(packageFixture.market,packageFixture.app,grants,skills,runtime,policy);
                    verify(requests,never()).persistActionStarted(any(),anyString(),anyString(),anyLong());assertEquals(7,evidence.queryForObject("SELECT COUNT(*) FROM writes",Integer.class));
                }
            }
        }
    }
}
