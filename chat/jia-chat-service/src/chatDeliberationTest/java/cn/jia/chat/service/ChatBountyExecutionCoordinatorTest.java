package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskExecutionGrantException;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatRequestEntity;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.deliberation.ChatInteractionStepStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatBountyExecutionCoordinatorTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final ChatInteractionStepStore steps=mock(ChatInteractionStepStore.class);
    private final ChatDeliberationDao requests=mock(ChatDeliberationDao.class);
    private final ChatConversationDao conversations=mock(ChatConversationDao.class);
    private final AgentTaskExecutionGrantService grants=mock(AgentTaskExecutionGrantService.class);
    private final AgentTaskRequirementSnapshotService requirements=mock(AgentTaskRequirementSnapshotService.class);
    private final PersonalWorkspaceExecutionService executions=mock(PersonalWorkspaceExecutionService.class);
    private final ChatBountyExecutionCoordinator coordinator=new ChatBountyExecutionCoordinator(jdbc,steps,
            requests,conversations,grants,requirements,executions,new ObjectMapper());
    private final ChatBountyExecutionCoordinator.Pending candidate=
            new ChatBountyExecutionCoordinator.Pending("0","owner","client","req","step");

    @Test void refusesMissingOrForeignStepWithoutOpeningProviderOrGrant() {
        assertEquals("NOT_PENDING",coordinator.coordinate(candidate));
        when(steps.findStep("0","owner","client","req",1,1))
                .thenReturn(new ChatInteractionStepStore.Step("step","0","someone-else","client",
                        "req",1,1,"42",1,"task",2,"grant",1,"agent","EXECUTE","ADMITTED",
                        0,"a".repeat(64),1,1));
        assertEquals("NOT_PENDING",coordinator.coordinate(candidate));
        verifyNoInteractions(grants,executions);
    }

    @Test void orphanRequestCannotStartOrClaimAnExecution() {
        when(steps.findStep("0","owner","client","req",1,1))
                .thenReturn(new ChatInteractionStepStore.Step("step","0","owner","client",
                        "req",1,1,"42",1,"task",2,"grant",1,"agent","EXECUTE","ADMITTED",
                        0,"a".repeat(64),1,1));
        assertThrows(IllegalStateException.class,()->coordinator.coordinate(candidate));
        verifyNoInteractions(grants,executions,conversations,requirements);
    }

    @Test void candidateLimitIsBoundedBeforeAnyDatabaseRead() {
        assertThrows(IllegalArgumentException.class,()->coordinator.pending(0));
        assertThrows(IllegalArgumentException.class,()->coordinator.pending(101));
        verifyNoInteractions(jdbc);
    }
    private void persistedImageRequest() throws Exception {
        String content="画一只鸟";
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                CanonicalContextJson.write(Map.of("taskId","task","assignmentRevision",2L,
                        "grantId","grant","grantVersion",1L,"targetAgentId","agent",
                        "operation","GENERATE_IMAGE","content",content))
                        .getBytes(StandardCharsets.UTF_8)));
        when(steps.findStep("0","owner","client","req",1,1))
                .thenReturn(new ChatInteractionStepStore.Step("step","0","owner","client",
                        "req",1,1,"42",1,"task",2,"grant",1,"agent","EXECUTE","ADMITTED",
                        0,digest,1,1));
        when(steps.findLink("0","owner","client","step"))
                .thenReturn(new ChatInteractionStepStore.ExecutionLink("intent","0","owner",
                        "client","step",null,"WAITING_ADMISSION",0,1,1));
        when(requests.findRequest("0","owner","client","req"))
                .thenReturn(new ChatRequestEntity().setTenantId("0").setOwnerJiacn("owner")
                        .setClientId("client").setRequestId("req").setRequestRevision(1L)
                        .setConversationId("42").setConversationGeneration(1L)
                        .setAggregateState("PLANNING").setUserMessageId(7L).setStateVersion(0L));
        // Exercise the real row mapping and metadata extraction without a test DB.
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
                any(Object[].class))).thenAnswer(invocation->{
            @SuppressWarnings("unchecked") RowMapper<Object> mapper=invocation.getArgument(1);
            var row=mock(java.sql.ResultSet.class);
            when(row.getString(1)).thenReturn(content);
            when(row.getString(2)).thenReturn("{\"requestId\":\"req\",\"taskId\":\"task\","
                    +"\"targetAgentId\":\"agent\",\"permittedOperation\":\"GENERATE_IMAGE\"}");
            return List.of(mapper.mapRow(row,0));
        });
    }

    @Test void legacyIntentCannotSilentlyDropGrantedImageReference() throws Exception {
        persistedImageRequest();
        when(grants.admitControlled(any(),eq("task"),eq("grant"),eq(1L),eq(2L),eq("agent"),
                eq("GENERATE_IMAGE"),eq("NEW_EXECUTION"),isNull(),isNull(),isNull()))
                .thenReturn(new AgentTaskExecutionGrantService.Admission("grant",1,2,
                        "agent","GENERATE_IMAGE",true,List.of(new AgentTaskExecutionGrantService.AuthorizedInput(
                                "file",1,"REFERENCE","image/png",4,"a".repeat(64))),
                        "mmd-ci-v1:consent_1234567890abcdef1234567890abcdef",2L));
        assertThrows(IllegalStateException.class,()->coordinator.coordinate(candidate));
        verifyNoInteractions(executions,conversations);
    }

    @Test void exactPersistedReferenceIsForwardedOnlyAfterMatchingLiveGrant() throws Exception {
        String hash="a".repeat(64), requirementHash="b".repeat(64), referencesHash="c".repeat(64);
        String snapshotDigest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                CanonicalContextJson.write(Map.of("requirementHash",requirementHash,
                        "referenceHash",referencesHash,"taskId","task","assignmentRevision",2L,
                        "grantId","grant","grantVersion",1L,"targetAgentId","agent",
                        "operation","GENERATE_IMAGE")).getBytes(StandardCharsets.UTF_8)));
        when(steps.findStep("0","owner","client","req",1,1))
                .thenReturn(new ChatInteractionStepStore.Step("step","0","owner","client",
                        "req",1,1,"42",1,"task",2,"grant",1,"agent","EXECUTE","ADMITTED",
                        0,snapshotDigest,1,1));
        when(requests.findRequest("0","owner","client","req"))
                .thenReturn(new ChatRequestEntity().setTenantId("0").setOwnerJiacn("owner")
                        .setClientId("client").setRequestId("req").setRequestRevision(1L)
                        .setConversationId("42").setConversationGeneration(1L)
                        .setAggregateState("PLANNING").setUserMessageId(7L).setStateVersion(0L));
        when(requirements.read(any(),eq("task"),eq(1L)))
                .thenReturn(new AgentTaskRequirementSnapshotService.Snapshot(
                        "0","client","owner","task",1,"画一只鸟",null,requirementHash,"task"));
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
                any(Object[].class))).thenAnswer(invocation->{
            @SuppressWarnings("unchecked") RowMapper<Object> mapper=invocation.getArgument(1);
            var row=mock(java.sql.ResultSet.class);
            when(row.getString(1)).thenReturn("画一只鸟");
            when(row.getString(2)).thenReturn("{\"requestId\":\"req\",\"taskId\":\"task\","
                    +"\"targetAgentId\":\"agent\",\"permittedOperation\":\"GENERATE_IMAGE\","
                    +"\"sourceBusinessActionId\":\"assignment\",\"requirementRevision\":1,"
                    +"\"requirementHash\":\""+requirementHash+"\",\"referenceSummarySha256\":\""
                    +referencesHash+"\",\"referenceSummaries\":[{\"fileId\":\"file\","
                    +"\"version\":1,\"purpose\":\"REFERENCE\",\"mimeType\":\"image/png\","
                    +"\"byteLength\":4,\"contentHash\":\""+hash+"\"}]}");
            return List.of(mapper.mapRow(row,0));
        });
        when(grants.admitControlled(any(),eq("task"),eq("grant"),eq(1L),eq(2L),eq("agent"),
                eq("GENERATE_IMAGE"),eq("NEW_EXECUTION"),isNull(),isNull(),isNull()))
                .thenReturn(new AgentTaskExecutionGrantService.Admission("grant",1,2,
                        "agent","GENERATE_IMAGE",true,List.of(new AgentTaskExecutionGrantService.AuthorizedInput(
                                "file",1,"REFERENCE","image/png",4,hash)),
                        "mmd-ci-v1:consent_1234567890abcdef1234567890abcdef",2L));
        when(conversations.lockScopedById("owner","client","42"))
                .thenReturn(new ChatConversationEntity().setId(42L).setTaskId("task")
                        .setConversationScopeType("bounty").setConversationScopeKey("task:task")
                        .setLifecycleGeneration(1L));
        when(steps.findLink("0","owner","client","step"))
                .thenReturn(new ChatInteractionStepStore.ExecutionLink("intent","0","owner",
                        "client","step",null,"WAITING_ADMISSION",0,1,1));
        when(executions.createConversation(any(),any())).thenReturn(new PersonalWorkspaceExecutionService.ExecutionView(
                "execution","task","run","42","agent","QUEUED",null,null,1,
                "image/png",List.of(),null,"CONVERSATION",null,null,null));
        when(steps.bindExecution(any(),eq("execution"),anyLong())).thenReturn(1);
        when(steps.updateStepState(any(),eq("RUNNING"),anyLong())).thenReturn(1);
        when(requests.updateRequestState(any(),eq("RUNNING"),anyLong())).thenReturn(1);
        assertEquals("RUNNING",coordinator.coordinate(candidate));
        verify(executions).createConversation(any(),argThat(command ->
                command.references().size()==1 && "file".equals(command.references().getFirst().fileId())
                        && command.references().getFirst().version()==1
                        && hash.equals(command.references().getFirst().contentHash())));
    }

    @Test void missingPaidAuthorizationIsPersistedAsWaitingWithoutCreatingExecution() throws Exception {
        persistedImageRequest();
        when(grants.admitControlled(any(),eq("task"),eq("grant"),eq(1L),eq(2L),eq("agent"),
                eq("GENERATE_IMAGE"),eq("NEW_EXECUTION"),isNull(),isNull(),isNull()))
                .thenThrow(new AgentTaskExecutionGrantException(
                        AgentTaskExecutionGrantException.Reason.PAID_EXECUTION_NOT_AUTHORIZED,"no cost authorization"));
        when(steps.updateStepState(any(),eq("WAITING_AUTHORIZATION"),anyLong())).thenReturn(1);
        when(requests.updateRequestState(any(),eq("WAITING_AUTHORIZATION"),anyLong())).thenReturn(1);
        assertEquals("WAITING_AUTHORIZATION",coordinator.coordinate(candidate));
        verifyNoInteractions(executions,conversations);
    }

    @Test void authorizedExactImageIntentBindsOneExecutionAndTransitionsRequest() throws Exception {
        persistedImageRequest();
        when(grants.admitControlled(any(),eq("task"),eq("grant"),eq(1L),eq(2L),eq("agent"),
                eq("GENERATE_IMAGE"),eq("NEW_EXECUTION"),isNull(),isNull(),isNull()))
                .thenReturn(new AgentTaskExecutionGrantService.Admission("grant",1,2,
                        "agent","GENERATE_IMAGE",true,List.of(),
                        "mmd-ci-v1:consent_1234567890abcdef1234567890abcdef",2L));
        var discussion=new ChatConversationEntity().setId(42L).setTaskId("task")
                .setConversationScopeType("bounty").setConversationScopeKey("task:task")
                .setLifecycleGeneration(1L);
        when(conversations.lockScopedById("owner","client","42")).thenReturn(discussion);
        when(steps.findLink("0","owner","client","step"))
                .thenReturn(new ChatInteractionStepStore.ExecutionLink("intent","0","owner",
                        "client","step",null,"WAITING_ADMISSION",0,1,1));
        when(executions.createConversation(any(),any())).thenReturn(new PersonalWorkspaceExecutionService.ExecutionView(
                "execution","task","run","42","agent","QUEUED",null,null,1,
                "image/png",List.of(),null,"CONVERSATION",null,null,null));
        when(steps.bindExecution(any(),eq("execution"),anyLong())).thenReturn(1);
        when(steps.updateStepState(any(),eq("RUNNING"),anyLong())).thenReturn(1);
        when(requests.updateRequestState(any(),eq("RUNNING"),anyLong())).thenReturn(1);
        assertEquals("RUNNING",coordinator.coordinate(candidate));
        verify(executions,times(1)).createConversation(any(),argThat(command ->
                command.intentId().equals("intent") && command.instruction().equals("画一只鸟")
                        && command.permittedOperation().equals("GENERATE_IMAGE") && command.controlledImage())));
    }

}
