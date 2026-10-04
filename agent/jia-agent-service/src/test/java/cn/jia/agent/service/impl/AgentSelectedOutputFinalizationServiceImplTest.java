package cn.jia.agent.service.impl;

import cn.jia.agent.entity.AgentTaskArtifactViewDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliveryViewDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskStateDTO;
import cn.jia.agent.entity.AgentWorkItemLeaseDTO;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.service.AgentSelectedOutputFinalizationException;
import cn.jia.agent.service.AgentSelectedOutputFinalizationService;
import cn.jia.agent.service.AgentTaskArtifactService;
import cn.jia.agent.service.AgentTaskExecutionGrantException;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskFormalDeliveryDecisionService;
import cn.jia.agent.service.AgentTaskFormalDeliveryReadService;
import cn.jia.agent.service.AgentTaskFormalDeliveryService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentTaskStateService;
import cn.jia.agent.service.AgentWorkItemLeaseService;
import cn.jia.agent.service.SelectedOutputFinalizationDigest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentSelectedOutputFinalizationServiceImplTest {
    @Test
    void actualHappyPathAtomicallyClaimsStartsPublishesSubmitsAndOwnerAcceptsWithoutLeakingToken() {
        Fixture f=new Fixture();
        var ready=f.service.prepare(f.scope,f.command("run-1"));
        assertEquals("READY_TO_SUBMIT",ready.stage());assertNull(ready.deliveryId());
        assertFalse(ready.toString().contains("lease-secret"));
        var submitted=f.service.submit(f.scope,"task-1","op-1",f.digest);
        assertEquals("submitted",submitted.deliveryState());assertEquals("SUBMITTED",submitted.stage());
        assertNull(f.jdbc.leaseToken);
        var completed=f.service.accept(f.scope,"task-1","op-1",f.digest);
        assertEquals("TASK_COMPLETED",completed.stage());assertEquals("accepted",completed.deliveryState());
        assertEquals("completed",completed.taskState());
        verify(f.leases).claim(anyString(),anyString(),anyString(),anyString(),eq("work-1"),any());
        verify(f.leases).start(anyString(),anyString(),anyString(),anyString(),eq("work-1"),any());
        verify(f.leases,never()).heartbeat(anyString(),anyString(),anyString(),anyString(),anyString(),any());
        var artifact=org.mockito.ArgumentCaptor.forClass(cn.jia.agent.entity.AgentTaskArtifactPublishDTO.class);
        verify(f.artifacts,times(2)).publish(eq("0"),eq("client-1"),eq("owner-1"),eq("task-1"),eq("agent-1"),artifact.capture());
        assertTrue(artifact.getAllValues().stream().allMatch(v->"agent-1".equals(v.getProducerAgentId())));
        verify(f.submissions).submit(eq("0"),eq("client-1"),eq("owner-1"),eq("task-1"),eq("agent-1"),any());
        verify(f.decisions).decide(eq("0"),eq("client-1"),eq("task-1"),eq("owner-1"),any());
    }

    @Test
    void sameBodyReplayCannotReplaceResolvedExecutionRunOrProducerFacts() {
        Fixture f=new Fixture();
        f.service.prepare(f.scope,f.command("run-1"));
        AgentSelectedOutputFinalizationException conflict=assertThrows(AgentSelectedOutputFinalizationException.class,
                ()->f.service.prepare(f.scope,f.command("forged-run")));
        assertEquals(AgentSelectedOutputFinalizationException.Reason.CONFLICT,conflict.reason());
        verify(f.leases,times(1)).claim(anyString(),anyString(),anyString(),anyString(),anyString(),any());
        verify(f.artifacts,times(2)).publish(anyString(),anyString(),anyString(),anyString(),anyString(),any());
    }

    @Test
    void revokedOrReassignedGrantFailsBeforeLeaseArtifactOrProviderAuthority() {
        Fixture f=new Fixture();
        when(f.grants.admitSelectedOutputPromotion(any(),anyString(),anyString(),anyLong(),anyLong(),anyString()))
                .thenThrow(new AgentTaskExecutionGrantException(AgentTaskExecutionGrantException.Reason.CONFLICT,"revoked"));
        AgentSelectedOutputFinalizationException denied=assertThrows(AgentSelectedOutputFinalizationException.class,
                ()->f.service.prepare(f.scope,f.command("run-1")));
        assertEquals(AgentSelectedOutputFinalizationException.Reason.GRANT_CHANGED,denied.reason());
        verifyNoInteractions(f.leases,f.artifacts,f.submissions,f.decisions);
        assertNull(f.jdbc.phase,"claiming authority must roll back with grant denial");
    }


    @Test
    void artifactAclFailureIsTranslatedWithoutExposingDependencyDetails(){
        Fixture f=new Fixture();
        when(f.artifacts.getVersion(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),eq(1)))
                .thenThrow(new AgentTaskCollaborationException(AgentTaskCollaborationException.Reason.FORBIDDEN,"hidden"));
        AgentSelectedOutputFinalizationException denied=assertThrows(AgentSelectedOutputFinalizationException.class,
                ()->f.service.prepare(f.scope,f.command("run-1")));
        assertEquals(AgentSelectedOutputFinalizationException.Reason.GRANT_CHANGED,denied.reason());
        assertFalse(denied.getMessage().contains("hidden"));
        verify(f.artifacts,never()).publish(anyString(),anyString(),anyString(),anyString(),anyString(),any());
        verifyNoInteractions(f.submissions,f.decisions);
    }

    @Test
    void persistedLeaseRecoveryRequiresExactPrivateTokenAndVersion() {
        Fixture f=new Fixture();
        f.service.prepare(f.scope,f.command("run-1"));
        f.jdbc.phase="LEASED";f.jdbc.leaseToken="other-token";f.jdbc.workToken="lease-secret";
        AgentSelectedOutputFinalizationException denied=assertThrows(AgentSelectedOutputFinalizationException.class,
                ()->f.service.prepare(f.scope,f.command("run-1")));
        assertEquals(AgentSelectedOutputFinalizationException.Reason.CONFLICT,denied.reason());
        verify(f.leases,times(1)).claim(anyString(),anyString(),anyString(),anyString(),anyString(),any());
    }


    private static AgentSelectedOutputFinalizationService.PrepareCommand selected(Fixture f,
            List<AgentSelectedOutputFinalizationService.SourceOutput> outputs) {
        String digest=SelectedOutputFinalizationDigest.request("task-1",7,7,"100","Owner selected this output",
                outputs.stream().map(o->new SelectedOutputFinalizationDigest.Selection(o.requestId(),o.stepId(),o.outputId(),
                        o.sha256(),o.title(),o.purpose(),o.messageSource())).toList());
        return new AgentSelectedOutputFinalizationService.PrepareCommand("op-1","task-1",7,7,"100",3,
                "agent-1","grant-1",2,"Owner selected this output",digest,outputs);
    }
    private static SelectedOutputFinalizationDigest.MessageSource message(String id) {
        return new SelectedOutputFinalizationDigest.MessageSource("turn-1",id,"snapshot-1","sha256:"+"a".repeat(64));
    }
    private static AgentSelectedOutputFinalizationService.SourceOutput text(String id,byte[] bytes) {
        return AgentSelectedOutputFinalizationService.SourceOutput.completedMessage("req-1",null,message(id),
                sha(bytes),"Text","final",bytes);
    }
    @Test void completedMessageUsesActualSnapshotAndExistingFormalDeliveryWithoutSourceExecution() {
        Fixture f=new Fixture(); byte[] bytes="原始正文\n第二行  \n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var output=text("9",bytes); var command=selected(f,List.of(output));
        bytes[0]=0; assertNotEquals(0,output.bytes()[0]);
        assertNull(output.stepId());assertNull(output.executionId());assertNull(output.runId());assertNull(output.outputId());
        assertEquals("READY_TO_SUBMIT",f.service.prepare(f.scope,command).stage());
        assertTrue(f.publishedManifest.get().contains("COMPLETED_MESSAGE"));assertTrue(f.publishedManifest.get().contains("snapshot-1"));
        assertFalse(f.publishedManifest.get().contains("stepId"));assertFalse(f.publishedManifest.get().contains("executionId"));assertFalse(f.publishedManifest.get().contains("outputId"));
        assertFalse(f.publishedManifest.get().contains("\"runId\""));
        assertEquals("SUBMITTED",f.service.submit(f.scope,"task-1","op-1",command.immutableDigest()).stage());
        assertEquals("TASK_COMPLETED",f.service.accept(f.scope,"task-1","op-1",command.immutableDigest()).stage());
        verify(f.leases).claim(anyString(),anyString(),anyString(),anyString(),eq("work-1"),any());
        verify(f.decisions).decide(eq("0"),eq("client-1"),eq("task-1"),eq("owner-1"),any());
    }
    @Test void completedMessageCannotCarryFabricatedExecutionAuthority() {
        Fixture f=new Fixture(); var real=text("9",f.bytes);
        var forged=new AgentSelectedOutputFinalizationService.SourceOutput(real.requestId(),real.stepId(),"fake-execution",
                "fake-run","fake-output",real.sha256(),real.contentMimeType(),real.byteLength(),real.title(),real.purpose(),
                real.bytes(),real.messageSource());
        assertThrows(AgentSelectedOutputFinalizationException.class,()->f.service.prepare(f.scope,selected(f,List.of(forged))));
        verifyNoInteractions(f.grants,f.leases,f.artifacts);assertNull(f.jdbc.phase);
    }
    @Test void invalidTextReferencesAndUtf8FailBeforeAnyPromotion() {
        for(String id:List.of("0","09","-1","9223372036854775808","99999999999999999999")) {
            Fixture f=new Fixture();var output=text(id,f.bytes);
            assertThrows(AgentSelectedOutputFinalizationException.class,()->f.service.prepare(f.scope,selected(f,List.of(output))));
            verifyNoInteractions(f.grants,f.leases,f.artifacts);
        }
        for(byte[] bytes:List.of(new byte[]{(byte)0xc3,0x28},"  \n".getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            Fixture f=new Fixture();var output=text("9",bytes);
            assertThrows(AgentSelectedOutputFinalizationException.class,()->f.service.prepare(f.scope,selected(f,List.of(output))));
            verifyNoInteractions(f.grants,f.leases,f.artifacts);
        }
    }
    @Test void duplicateTextSourcesFailAndMixedTextAndMediaRetainBothSources() {
        Fixture f=new Fixture();var output=text("9",f.bytes);
        assertThrows(AgentSelectedOutputFinalizationException.class,()->f.service.prepare(f.scope,selected(f,List.of(output,output))));
        verifyNoInteractions(f.grants,f.leases,f.artifacts);
        var mixed=selected(f,List.of(output,f.command("run-1").outputs().getFirst()));
        assertEquals("READY_TO_SUBMIT",f.service.prepare(f.scope,mixed).stage());
        assertTrue(f.publishedManifest.get().contains("COMPLETED_MESSAGE"));assertTrue(f.publishedManifest.get().contains("out-1"));
        verify(f.artifacts,times(3)).publish(anyString(),anyString(),anyString(),anyString(),anyString(),any());
    }
    @Test void messageReferenceDriftCannotReplaceExistingOperation() {
        Fixture f=new Fixture();var first=selected(f,List.of(text("9",f.bytes)));
        f.service.prepare(f.scope,first);
        assertThrows(AgentSelectedOutputFinalizationException.class,()->f.service.prepare(f.scope,selected(f,List.of(text("10",f.bytes)))));
        verify(f.leases,times(1)).claim(anyString(),anyString(),anyString(),anyString(),anyString(),any());
    }
    @Test void originalMediaDigestRemainsByteExact() {
        assertEquals("491dfdc59d2609557394df265ad68a3570138d8156c5dff016701a2ea199d601",new Fixture().digest);
    }

    @Test void textMimeHashAndLengthAreNotBrowserAuthority() {
        for(String changed:List.of("mime","hash","length","step")) {
            Fixture f=new Fixture();var real=text("9",f.bytes);
            var forged=new AgentSelectedOutputFinalizationService.SourceOutput(real.requestId(),"step".equals(changed)?"made-up-step":null,
                    null,null,null,"hash".equals(changed)?"0".repeat(64):real.sha256(),"mime".equals(changed)?"image/png":real.contentMimeType(),
                    "length".equals(changed)?real.byteLength()+1:real.byteLength(),real.title(),real.purpose(),real.bytes(),real.messageSource());
            assertThrows(AgentSelectedOutputFinalizationException.class,()->f.service.prepare(f.scope,selected(f,List.of(forged))));
            verifyNoInteractions(f.grants,f.leases,f.artifacts);
        }
    }

    static final class Fixture {
        final FakeJdbc jdbc=new FakeJdbc();
        final DirectTransactions transactions=new DirectTransactions(jdbc);
        final AgentTaskExecutionGrantService grants=mock(AgentTaskExecutionGrantService.class);
        final AgentWorkItemLeaseService leases=mock(AgentWorkItemLeaseService.class);
        final AgentTaskStateService states=mock(AgentTaskStateService.class);
        final AgentTaskArtifactService artifacts=mock(AgentTaskArtifactService.class);
        final AgentTaskFormalDeliveryService submissions=mock(AgentTaskFormalDeliveryService.class);
        final AgentTaskFormalDeliveryReadService reads=mock(AgentTaskFormalDeliveryReadService.class);
        final AgentTaskFormalDeliveryDecisionService decisions=mock(AgentTaskFormalDeliveryDecisionService.class);
        final AgentSelectedOutputFinalizationServiceImpl service;
        final AgentSelectedOutputFinalizationService.Scope scope=
                new AgentSelectedOutputFinalizationService.Scope("0","client-1","owner-1");
        final byte[] bytes="selected bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final String hash=sha(bytes);
        final String digest=SelectedOutputFinalizationDigest.request("task-1",7,7,"100",
                "Owner selected this output",List.of(new SelectedOutputFinalizationDigest.Selection(
                        "req-1","step-1","out-1",hash,"Hero","final")));
        final AtomicReference<AgentTaskFormalDeliveryViewDTO> delivery=new AtomicReference<>();
        final AtomicReference<String> publishedManifest=new AtomicReference<>();
        Fixture(){
            var admission=new AgentTaskExecutionGrantService.Admission(
                    "grant-1",2,7,"agent-1","FINALIZE_SELECTED_OUTPUTS",false,List.of());
            when(grants.admitSelectedOutputPromotion(any(),eq("task-1"),eq("grant-1"),eq(2L),eq(7L),eq("agent-1")))
                    .thenReturn(admission);
            when(leases.claim(anyString(),anyString(),anyString(),anyString(),anyString(),any()))
                    .thenReturn(lease("claimed",4,"lease-secret"));
            when(leases.start(anyString(),anyString(),anyString(),anyString(),anyString(),any()))
                    .thenReturn(lease("running",5,"lease-secret"));
            when(leases.validateLeaseForResult(anyString(),anyString(),anyString(),anyString(),anyString(),any()))
                    .thenReturn(lease("running",5,"lease-secret"));
            when(states.transitionTask(anyString(),anyString(),anyString(),eq("task-1"),any())).thenAnswer(i->{
                jdbc.taskState="running";jdbc.taskVersion=8;AgentTaskStateDTO dto=new AgentTaskStateDTO();
                dto.setStatus("running");dto.setVersion(8L);return dto;});
            when(artifacts.getVersion(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),eq(1)))
                    .thenThrow(new AgentTaskCollaborationException(AgentTaskCollaborationException.Reason.NOT_FOUND,"absent"));
            when(artifacts.publish(anyString(),anyString(),anyString(),eq("task-1"),eq("agent-1"),any())).thenAnswer(i->{
                var c=(cn.jia.agent.entity.AgentTaskArtifactPublishDTO)i.getArgument(5);
                if ("application/json".equals(c.getContentMimeType())) publishedManifest.set(new String(c.getContentBytes(),java.nio.charset.StandardCharsets.UTF_8));
                AgentTaskArtifactViewDTO v=new AgentTaskArtifactViewDTO();v.setArtifactId(c.getArtifactId());
                v.setTaskId("task-1");v.setWorkItemId("work-1");v.setProducerAgentId("agent-1");
                v.setArtifactType(c.getArtifactType());v.setTitle(c.getTitle());v.setVisibility("task_members");
                v.setArtifactVersion(1);v.setContentHash(c.getContentHash());v.setMetadata(c.getMetadata());
                v.setContentByteLength(c.getContentByteLength());v.setContentMimeType(c.getContentMimeType());
                v.setManagedStorage(true);v.setStorageUri("task-artifacts-private://fixture/"+c.getArtifactId());return v;});
            when(reads.listForTaskOwner(anyString(),anyString(),anyString(),eq("task-1")))
                    .thenAnswer(i->delivery.get()==null?List.of():List.of(delivery.get()));
            when(submissions.submit(anyString(),anyString(),anyString(),eq("task-1"),eq("agent-1"),any())).thenAnswer(i->{
                AgentTaskFormalDeliveryViewDTO v=formal(jdbc.deliveryId,"submitted",9,0);delivery.set(v);
                jdbc.taskState="reviewing";jdbc.taskVersion=9;return v;});
            when(decisions.decide(anyString(),anyString(),eq("task-1"),anyString(),any())).thenAnswer(i->{
                AgentTaskFormalDeliveryViewDTO v=formal(jdbc.deliveryId,"accepted",10,1);delivery.set(v);
                jdbc.taskState="completed";jdbc.taskVersion=10;return v;});
            service=new AgentSelectedOutputFinalizationServiceImpl(jdbc,transactions,grants,leases,states,
                    artifacts,submissions,reads,decisions,new ObjectMapper());
        }
        AgentSelectedOutputFinalizationService.PrepareCommand command(String runId){
            return new AgentSelectedOutputFinalizationService.PrepareCommand("op-1","task-1",7,7,"100",3,
                    "agent-1","grant-1",2,"Owner selected this output",digest,List.of(
                    new AgentSelectedOutputFinalizationService.SourceOutput("req-1","step-1","exec-1",runId,
                            "out-1",hash,"image/png",bytes.length,"Hero","final",bytes)));
        }
    }

    private static AgentWorkItemLeaseDTO lease(String state,long version,String token){
        AgentWorkItemLeaseDTO v=new AgentWorkItemLeaseDTO();v.setTaskId("task-1");v.setWorkItemId("work-1");
        v.setAgentId("agent-1");v.setStatus(state);v.setVersion(version);v.setLeaseToken(token);
        v.setLeaseUntil(System.currentTimeMillis()+900000);return v;
    }
    private static AgentTaskFormalDeliveryViewDTO formal(String id,String state,long taskVersion,long deliveryVersion){
        AgentTaskFormalDeliveryViewDTO v=new AgentTaskFormalDeliveryViewDTO();v.setTaskId("task-1");
        v.setDeliveryId(id);v.setState(state);v.setTaskVersion(taskVersion);v.setDeliveryVersion(deliveryVersion);return v;
    }
    private static String sha(byte[] value){try{return java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception e){throw new AssertionError(e);}}

    static final class DirectTransactions implements AgentTaskMutationTransaction {
        private final FakeJdbc jdbc;DirectTransactions(FakeJdbc jdbc){this.jdbc=jdbc;}
        private <T>T apply(LockedTaskMutation<T> mutation){
            String phase=jdbc.phase;try{return mutation.apply(new AgentTaskMetaEntity());}
            catch(RuntimeException failure){if(phase==null)jdbc.clearAuthority();throw failure;}
        }
        @Override public <T>T executeWithLockedTaskRoot(String t,String c,String task,LockedTaskMutation<T> m){return apply(m);}
        @Override public <T>T executeWithLockedTaskRootInOwnerScope(String t,String c,String o,String task,LockedTaskMutation<T> m){return apply(m);}
        @Override public <T>T executeWithLockedTaskRootForWorkItem(String t,String c,String w,LockedTaskMutation<T> m){return apply(m);}
        @Override public <T>T executeWithLockedTaskRootForWorkItemInOwnerScope(String t,String c,String o,String w,LockedTaskMutation<T> m){return apply(m);}
        @Override public <T>T executeAfterTaskRootReservation(String t,String c,String task,TaskRootReservation r,ReservedTaskMutation<T> m){return m.apply(new AgentTaskMetaEntity(),r.reserve()==1);}
        @Override public <T>T executeAfterTaskRootReservationInOwnerScope(String t,String c,String o,String task,TaskRootReservation r,ReservedTaskMutation<T> m){return m.apply(new AgentTaskMetaEntity(),r.reserve()==1);}
    }

    static final class FakeJdbc extends JdbcTemplate {
        String phase,digest,sourceDigest,operationId,taskId,conversationId,target,grant,runId,deliveryId,manifest,summary;
        String workItem,leaseToken,workToken;Long leaseVersion;String facts;long created;long generation;
        String taskState="assigned";long taskVersion=7;String workState="ready";long workVersion=3;
        void clearAuthority(){phase=null;digest=null;sourceDigest=null;operationId=null;taskId=null;}
        @Override public int update(String sql,Object...args){
            if(sql.contains("INSERT INTO agent_selected_output_finalization")){
                operationId=(String)args[0];taskId=(String)args[4];digest=(String)args[5];sourceDigest=(String)args[6];
                conversationId=(String)args[9];generation=(Long)args[10];target=(String)args[11];grant=(String)args[12];
                runId=(String)args[14];summary=(String)args[15];deliveryId=(String)args[16];manifest=(String)args[17];
                phase="CLAIMING";created=(Long)args[18];return 1;}
            if(sql.contains("phase='LEASED'")){workItem=(String)args[0];leaseToken=(String)args[1];workToken=leaseToken;
                leaseVersion=(Long)args[2];phase="LEASED";workState="running";workVersion=leaseVersion;return 1;}
            if(sql.contains("phase='READY'")){facts=(String)args[0];phase="READY";return 1;}
            if(sql.contains("phase='SUBMITTED'")){leaseToken=null;phase="SUBMITTED";return 1;}
            if(sql.contains("phase='ACCEPTED'")){phase="ACCEPTED";return 1;}
            return 1;
        }
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object...args){try{
            ResultSet rs=mock(ResultSet.class);
            if(sql.contains("FROM agent_selected_output_finalization")){if(phase==null)return List.of();
                when(rs.getString(1)).thenReturn(operationId);when(rs.getString(2)).thenReturn(taskId);
                when(rs.getString(3)).thenReturn(digest);when(rs.getString(4)).thenReturn(sourceDigest);
                when(rs.getLong(5)).thenReturn(7L);when(rs.getLong(6)).thenReturn(7L);
                when(rs.getString(7)).thenReturn(conversationId);when(rs.getLong(8)).thenReturn(generation);
                when(rs.getString(9)).thenReturn(target);when(rs.getString(10)).thenReturn(grant);
                when(rs.getLong(11)).thenReturn(2L);when(rs.getString(12)).thenReturn(workItem);
                when(rs.getString(13)).thenReturn(runId);when(rs.getString(14)).thenReturn(summary);
                when(rs.getString(15)).thenReturn(leaseToken);when(rs.getObject(16)).thenReturn(leaseVersion);
                when(rs.getString(17)).thenReturn(deliveryId);when(rs.getString(18)).thenReturn(manifest);
                when(rs.getString(19)).thenReturn(facts);when(rs.getString(20)).thenReturn(phase);
                when(rs.getLong(21)).thenReturn(created);when(rs.getLong(22)).thenReturn(created);
                return List.of(mapper.mapRow(rs,0));}
            if(sql.contains("FROM agent_task_work_item")){
                when(rs.getString(1)).thenReturn("work-1");when(rs.getString(2)).thenReturn(workState);
                when(rs.getString(3)).thenReturn("ready".equals(workState)?null:"agent-1");
                when(rs.getString(4)).thenReturn(workToken);when(rs.getObject(5)).thenReturn(workToken==null?null:System.currentTimeMillis()+900000);
                when(rs.getLong(6)).thenReturn(workVersion);when(rs.getObject(7)).thenReturn(created+1);
                return List.of(mapper.mapRow(rs,0));}
            if(sql.contains("FROM agent_task_meta")){when(rs.getString(1)).thenReturn(taskState);
                when(rs.getLong(2)).thenReturn(taskVersion);return List.of(mapper.mapRow(rs,0));}
            return List.of();
        }catch(Exception e){throw new RuntimeException(e);}}
    }
}
