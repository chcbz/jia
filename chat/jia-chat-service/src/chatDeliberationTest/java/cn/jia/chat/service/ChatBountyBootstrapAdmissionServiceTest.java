package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskBountyBootstrapClaimDTO;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.ChatConversationEventEntity;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatInteractionStepStore;
import cn.jia.chat.deliberation.ChatRequestEntity;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatBountyBootstrapAdmissionServiceTest {
    private final AgentTaskRequirementSnapshotService requirements = mock(AgentTaskRequirementSnapshotService.class);
    private final ChatBountyConversationService discussions = mock(ChatBountyConversationService.class);
    private final ChatConversationDao conversations = mock(ChatConversationDao.class);
    private final ChatMessageDao messages = mock(ChatMessageDao.class);
    private final ChatDeliberationDao deliberation = mock(ChatDeliberationDao.class);
    private final ChatInteractionStepStore steps = mock(ChatInteractionStepStore.class);
    private final ChatConversationEventBroker broker = mock(ChatConversationEventBroker.class);
    private final ChatTypedDiscussionAdmissionService typedDiscussion = mock(ChatTypedDiscussionAdmissionService.class);
    private final ChatBountyBootstrapAdmissionService service = new ChatBountyBootstrapAdmissionService(
            requirements, discussions, conversations, messages, deliberation, steps, broker,
            JsonUtil.getMapper(), typedDiscussion);
    private final AgentTaskExecutionGrantService.Scope scope =
            new AgentTaskExecutionGrantService.Scope("0", "client", "owner");
    private final String snapshotHash = "a".repeat(64);

    private AgentTaskBountyBootstrapClaimDTO claim(String owner, String summaryHash) {
        var references = List.of(new AgentTaskBountyBootstrapClaimDTO.ReferenceSummary(
                "photo-1", 1, "REFERENCE", "image/png", 1024L, "b".repeat(64)));
        return new AgentTaskBountyBootstrapClaimDTO("bootstrap-1", "0", "client", owner,
                "task-1", "action-1", 1L, "TASK_REQUIREMENT_REVISION_V1", 3L,
                "agent-1", "grant-1", 1L, "GENERATE_IMAGE", references,
                summaryHash, "worker-1", System.currentTimeMillis() + 60_000, 1, 2);
    }

    private AgentTaskBountyBootstrapClaimDTO claim() throws Exception {
        var value = claim("owner", "f".repeat(64));
        return claim("owner", sha(JsonUtil.getMapper().writeValueAsString(value.references())));
    }

    private static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private ChatConversationEntity conversation() {
        var row = new ChatConversationEntity().setId(10L).setJiacn("owner")
                .setTaskId("task-1").setConversationType("juyiting")
                .setConversationScopeType("bounty").setLifecycleGeneration(1L);
        row.setTenantId("0"); row.setClientId("client");
        return row;
    }

    private void authorized() {
        when(requirements.read(scope, "task-1", 1L)).thenReturn(
                new AgentTaskRequirementSnapshotService.Snapshot("0", "client", "owner",
                        "task-1", 1L, "画一只鸟", "蓝色羽毛", snapshotHash, "CREATE"));
        when(discussions.ensure(scope, "task-1", "grant-1", 1L, 3L,
                "agent-1", "GENERATE_IMAGE", "画一只鸟"))
                .thenReturn(new ChatBountyConversationService.Discussion("10", 1L, true));
        when(conversations.lockScopedById("owner", "client", "10")).thenReturn(conversation());
        when(messages.insertScoped(eq("0"), eq("client"), any())).thenAnswer(inv -> {
            ChatMessageEntity row = inv.getArgument(2);
            row.setId(42L);
            return 1;
        });
        when(deliberation.insertRequest(any())).thenAnswer(inv -> {
            ChatRequestEntity row = inv.getArgument(0);
            row.setId(23L);
            return 1;
        });
        when(steps.insertStep(any())).thenReturn(1);
        when(steps.insertLink(any())).thenReturn(1);
        when(deliberation.insertEvent(any())).thenAnswer(inv -> {
            ChatConversationEventEntity row = inv.getArgument(0);
            row.setEventSequence(77L);
            return 1;
        });
        when(deliberation.assignEventVersion(77L)).thenReturn(1);
    }

    private AgentTaskBountyBootstrapClaimDTO genericClaim(
            List<AgentTaskBountyBootstrapClaimDTO.ReferenceSummary> references) throws Exception {
        return new AgentTaskBountyBootstrapClaimDTO("bootstrap-generic", "0", "client", "owner",
                "task-1", "action-generic", 1L, "TASK_REQUIREMENT_REVISION_V1", 3L,
                "agent-1", "grant-1", 1L, "DELIBERATE", references,
                sha(JsonUtil.getMapper().writeValueAsString(references)), "worker-1",
                System.currentTimeMillis()+60_000, 1, 2);
    }

    private cn.jia.chat.api.ChatTypedDeliberationWire.Accepted typedReceipt(boolean replay) {
        return new cn.jia.chat.api.ChatTypedDeliberationWire.Accepted(1,"DISCUSSION","request-typed",
                "42",List.of("turn-typed"),"ADMITTED","0","7","/chat/requests/request-typed",
                "/chat/conversations/10/requests/request-typed/typed-outcome",replay,null);
    }

    @Test void genericBootstrapSendsExactRequirementAndAll32MixedMaterialsToTypedDiscussion() throws Exception {
        authorized();
        when(discussions.ensure(scope,"task-1","grant-1",1,3,"agent-1","DELIBERATE","画一只鸟"))
                .thenReturn(new ChatBountyConversationService.Discussion("10",1,true));
        when(typedDiscussion.admit(eq("0"),any(),eq("10"),anyString(),any())).thenReturn(typedReceipt(false));
        var refs=new java.util.ArrayList<AgentTaskBountyBootstrapClaimDTO.ReferenceSummary>();
        var mimes=List.of("image/png","application/pdf","audio/ogg","text/plain");
        for(int i=0;i<32;i++)refs.add(new AgentTaskBountyBootstrapClaimDTO.ReferenceSummary(
                "file-"+i,1,"INPUT",mimes.get(i%4),100,"b".repeat(64)));
        var accepted=service.admit(genericClaim(refs));
        assertEquals("request-typed",accepted.requestId()); assertNull(accepted.stepId());
        var command=org.mockito.ArgumentCaptor.forClass(cn.jia.chat.api.ChatTypedDeliberationWire.DiscussionCommand.class);
        verify(typedDiscussion).admit(eq("0"),eq(new ServerResolvedSender("user","owner","owner","client",DisplayNameSource.JIACN)),
                eq("10"),anyString(),command.capture());
        assertEquals("画一只鸟\n\n蓝色羽毛",command.getValue().content());
        assertEquals("task-1",command.getValue().taskId());assertEquals(3,command.getValue().expectedAssignmentRevision());
        assertEquals(32,command.getValue().sourceSelectors().size());
        assertTrue(command.getValue().sourceSelectors().stream().allMatch(x->"INPUT".equals(x.purpose())));
        verifyNoInteractions(steps,messages,deliberation,broker);
    }

    @Test void genericTextOnlyBootstrapUsesSameTypedKeyOnReplayAndNoExecutionStep() throws Exception {
        authorized();
        when(discussions.ensure(scope,"task-1","grant-1",1,3,"agent-1","DELIBERATE","画一只鸟"))
                .thenReturn(new ChatBountyConversationService.Discussion("10",1,true));
        when(typedDiscussion.admit(eq("0"),any(),eq("10"),anyString(),any()))
                .thenReturn(typedReceipt(false),typedReceipt(true));
        var claim=genericClaim(List.of());
        var first=service.admit(claim); var again=service.admit(claim);
        assertFalse(first.replay());assertTrue(again.replay());assertEquals(first.requestId(),again.requestId());
        var keys=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(typedDiscussion,times(2)).admit(eq("0"),any(),eq("10"),keys.capture(),
                argThat(c->"DISCUSSION".equals(c.intent())&&c.sourceSelectors().isEmpty()));
        assertEquals(keys.getAllValues().getFirst(),keys.getAllValues().getLast());
        verifyNoInteractions(steps,messages,deliberation,broker);
    }

    @Test void identicalRequirementFieldsAreSentOnceWithoutRewritingTheSnapshot() throws Exception {
        assertGenericRequirementContent("画一只鸟", "画一只鸟", "画一只鸟");
    }

    @Test void repeatedParagraphsWithinOneRequirementFieldArePreserved() throws Exception {
        String repeated = "画一只鸟\n\n画一只鸟";
        assertGenericRequirementContent(repeated, repeated, repeated);
    }

    @Test void distinctDescriptionIsPreservedEvenWhenItStartsWithTheTitle() throws Exception {
        assertGenericRequirementContent("画一只鸟", "画一只鸟，蓝色羽毛", "画一只鸟\n\n画一只鸟，蓝色羽毛");
    }

    private void assertGenericRequirementContent(String title, String description, String expected) throws Exception {
        authorized();
        when(requirements.read(scope, "task-1", 1L)).thenReturn(
                new AgentTaskRequirementSnapshotService.Snapshot("0", "client", "owner",
                        "task-1", 1L, title, description, snapshotHash, "CREATE"));
        when(discussions.ensure(scope, "task-1", "grant-1", 1L, 3L, "agent-1", "DELIBERATE", title))
                .thenReturn(new ChatBountyConversationService.Discussion("10", 1L, true));
        when(typedDiscussion.admit(eq("0"), any(), eq("10"), anyString(), any()))
                .thenReturn(typedReceipt(false));
        service.admit(genericClaim(List.of()));
        var command = org.mockito.ArgumentCaptor.forClass(cn.jia.chat.api.ChatTypedDeliberationWire.DiscussionCommand.class);
        verify(typedDiscussion).admit(eq("0"), any(), eq("10"), anyString(), command.capture());
        assertEquals(expected, command.getValue().content());
        verify(requirements).read(scope, "task-1", 1L);
        verifyNoInteractions(messages, steps, deliberation, broker);
    }

    @Test void genericBootstrapDoesNotFallbackToImageIfTypedRuntimeIsUnavailable() throws Exception {
        authorized();
        when(discussions.ensure(scope,"task-1","grant-1",1,3,"agent-1","DELIBERATE","画一只鸟"))
                .thenReturn(new ChatBountyConversationService.Discussion("10",1,true));
        when(typedDiscussion.admit(eq("0"),any(),eq("10"),anyString(),any()))
                .thenThrow(new ChatDeliberationException(ChatDeliberationException.Reason.PERSISTENCE_ERROR,"Runtime unavailable"));
        assertThrows(ChatDeliberationException.class,()->service.admit(genericClaim(List.of())));
        verifyNoInteractions(steps,messages,deliberation,broker);
    }

    @Test void acceptedInitialRequestIsNotDispatchedAsChatOrMarkedAsGenerated() throws Exception {
        authorized();
        var admitted = service.admit(claim());
        assertEquals("10", admitted.conversationId());
        assertEquals("42", admitted.userMessageId());
        assertFalse(admitted.replay());
        var message = org.mockito.ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(messages).insertScoped(eq("0"), eq("client"), message.capture());
        assertEquals("画一只鸟\n\n蓝色羽毛", message.getValue().getContent());
        assertFalse(message.getValue().getMetadata().contains("/home/"));
        assertTrue(message.getValue().getMetadata().contains("\"permittedOperation\":\"GENERATE_IMAGE\""));
        var request = org.mockito.ArgumentCaptor.forClass(ChatRequestEntity.class);
        verify(deliberation).insertRequest(request.capture());
        assertEquals("PLANNING", request.getValue().getAggregateState());
        var step = org.mockito.ArgumentCaptor.forClass(ChatInteractionStepStore.Step.class);
        verify(steps).insertStep(step.capture());
        assertEquals("EXECUTE", step.getValue().kind());
        assertEquals("ADMITTED", step.getValue().state());
        var link = org.mockito.ArgumentCaptor.forClass(ChatInteractionStepStore.ExecutionLink.class);
        verify(steps).insertLink(link.capture());
        assertNull(link.getValue().executionId());
        assertEquals("WAITING_ADMISSION", link.getValue().state());
        verify(deliberation, never()).insertTurn(any());
        verify(deliberation, never()).insertOutbox(any());
    }

    @Test void replayReturnsExactRequestAndNeverDuplicatesUserMessageOrPaidIntent() throws Exception {
        authorized();
        var claim = claim();
        var first = service.admit(claim);
        AtomicReference<ChatRequestEntity> persisted = new AtomicReference<>();
        var request = org.mockito.ArgumentCaptor.forClass(ChatRequestEntity.class);
        verify(deliberation).insertRequest(request.capture());
        persisted.set(request.getValue());
        when(deliberation.findRequest("0", "owner", "client", first.requestId()))
                .thenReturn(persisted.get());
        var step = org.mockito.ArgumentCaptor.forClass(ChatInteractionStepStore.Step.class);
        verify(steps).insertStep(step.capture());
        when(steps.findStep("0", "owner", "client", first.requestId(), 1, 1))
                .thenReturn(step.getValue());
        var link = org.mockito.ArgumentCaptor.forClass(ChatInteractionStepStore.ExecutionLink.class);
        verify(steps).insertLink(link.capture());
        when(steps.findLink("0", "owner", "client", first.stepId()))
                .thenReturn(link.getValue());
        var second = service.admit(claim);
        assertTrue(second.replay());
        assertEquals(first.requestId(), second.requestId());
        verify(messages, times(1)).insertScoped(anyString(), anyString(), any());
        verify(steps, times(1)).insertLink(any());
        verify(deliberation, times(1)).insertEvent(any());
    }

    @Test void foreignOrCorruptRevisionAndReferenceNeverCreateRequest() throws Exception {
        var valid = claim();
        assertThrows(IllegalStateException.class, () -> service.admit(valid));
        verifyNoInteractions(discussions, messages, deliberation);
        reset(requirements);
        authorized();
        var changed = claim("owner", "f".repeat(64));
        assertThrows(IllegalStateException.class, () -> service.admit(changed));
        verifyNoInteractions(messages, deliberation);
        reset(requirements);
        var foreign = claim("attacker", valid.referenceSummarySha256());
        assertThrows(IllegalStateException.class, () -> service.admit(foreign));
        verifyNoInteractions(messages, deliberation);
    }

    @Test void failedPersistentStepCannotBeAcknowledgedAsAdmitted() throws Exception {
        authorized();
        when(steps.insertStep(any())).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.admit(claim()));
        verify(deliberation, never()).insertEvent(any());
    }
}
