package cn.jia.chat.service;

import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.*;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Receipt is part of the existing final write, not an independent progress transaction. */
class ChatContextReceiptFinalTest {
    final ChatDeliberationDao dao = mock(ChatDeliberationDao.class);
    final ChatConversationDao conversations = mock(ChatConversationDao.class);
    final ChatMessageDao messages = mock(ChatMessageDao.class);
    final ChatDeliberationService service = new ChatDeliberationService(dao, conversations, messages, mock(AgentService.class));
    final ChatContextDelivery.Plan plan = ChatContextDelivery.prepare(true, null, 102).orElseThrow();
    final Map<String, Object> receipt = new ChatContextDelivery.Receipt(
            ChatContextDelivery.Mode.BOOTSTRAP, "native-A", "native-turn-1", 102).wire();
    final ChatTurnEntity turn = new ChatTurnEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client")
            .setConversationId("1001").setConversationGeneration(1L).setRequestId("request").setRequestRevision(1L)
            .setTurnId("turn").setDispatchId("dispatch").setSnapshotId("snapshot").setContextDigest("sha256:ctx")
            .setTargetAgentId("agent").setRoute("CHAT").setState("STREAMING").setStateVersion(1L);
    ChatMessageEntity persisted;

    void setup(boolean withPlan) {
        var authority = mock(ChatDispatchAuthorityService.class);
        when(authority.incoming("0", "client", "owner", "agent", "dispatch"))
                .thenReturn(new ChatDispatchAuthorityService.Binding(null, turn, "owner"));
        service.setDispatchAuthority(authority);
        var conversation = new ChatConversationEntity().setLifecycleGeneration(1L).setConversationType("juyiting");
        conversation.setTenantId("0");
        when(conversations.findScopedById("0", "owner", "client", "1001")).thenReturn(conversation);
        when(conversations.lockScopedById("0", "owner", "client", "1001")).thenReturn(conversation);
        when(dao.findTurn("0", "owner", "client", "turn")).thenReturn(turn);
        when(dao.lockTurn("0", "owner", "client", "turn")).thenReturn(turn);
        when(dao.findSnapshot("0", "owner", "client", "snapshot")).thenReturn(new ChatContextSnapshotEntity()
                .setConversationId("1001").setConversationGeneration(1L).setRequestId("request").setRequestRevision(1L)
                .setTargetAgentId("agent").setContextDigest("sha256:ctx")
                .setFactsManifestJson(JsonUtil.toJson(withPlan ? Map.of("contextDelivery", plan.wire()) : Map.of())));
        var request = new ChatRequestEntity().setConversationId("1001").setConversationGeneration(1L)
                .setUserMessageId(102L).setAggregateState("RUNNING");
        when(dao.findRequest("0", "owner", "client", "request")).thenReturn(request);
        when(dao.findTurnsByRequest("0", "owner", "client", "request")).thenReturn(List.of(turn));
        when(dao.updateRequestState(eq(request), anyString(), anyLong())).thenReturn(1);
        when(messages.insertScoped(eq("0"), eq("client"), any())).thenAnswer(call -> {
            persisted = call.getArgument(2); persisted.setId(104L); return 1;
        });
        when(messages.selectById(104L)).thenAnswer(call -> persisted);
        when(dao.persistFinal(eq(turn), anyString(), eq(104L), anyLong())).thenReturn(1);
        when(dao.insertEvent(any())).thenAnswer(call -> {
            ChatConversationEventEntity event = call.getArgument(0); event.setEventSequence(201L); return 1;
        });

        when(dao.insertOutbox(any())).thenReturn(1);
    }
    ChatDeliberationService.FinalResult complete(Object raw) {
        return service.persistFinal("0", "owner", "client", "1001", 1L, "agent", "request", "turn", "dispatch",
                "snapshot", "sha256:ctx", "answer", null, null, null, raw,
                new ServerResolvedAgentSender("agent", "好汉", "owner", "client", "agent"));
    }
    @Test void receiptUsesExistingFinalMessageWriteAndIsIdempotent() throws Exception {
        setup(true);
        assertEquals(ChatDeliberationService.FinalStatus.PERSISTED, complete(receipt).status());
        Map<?, ?> metadata = JsonUtil.getMapper().readValue(persisted.getMetadata(), Map.class);
        assertEquals(receipt, metadata.get("contextReceipt"));
        assertEquals("102", receipt.get("throughMessageId")); // Never advance to assistant final ID 104.
        verify(messages, never()).selectById(anyLong());
        assertEquals(ChatDeliberationService.FinalStatus.DUPLICATE, complete(receipt).status());
        verify(messages, times(1)).insertScoped(eq("0"), eq("client"), any());
        verify(dao, times(1)).persistFinal(any(), anyString(), anyLong(), anyLong());
        var changed = new java.util.LinkedHashMap<>(receipt);
        changed.put("engineTurnId", "another-native-turn");
        assertThrows(ChatDeliberationException.class, () -> complete(changed));
        verify(dao, times(1)).insertOutbox(any());
    }
    @Test void missingOrMismatchedReceiptCannotPersistFinalOrProgress() {
        setup(true);
        assertThrows(IllegalArgumentException.class, () -> complete(null));
        var changed = new java.util.LinkedHashMap<>(receipt); changed.put("throughMessageId", "104");
        assertThrows(IllegalArgumentException.class, () -> complete(changed));
        verify(messages, never()).insertScoped(anyString(), anyString(), any());
        verify(dao, never()).persistFinal(any(), anyString(), anyLong(), anyLong());
    }
    @Test void priorSnapshotStillCompletesWithoutManufacturingAReceipt() throws Exception {
        setup(false);
        assertEquals(ChatDeliberationService.FinalStatus.PERSISTED, complete(null).status());
        Map<?, ?> metadata = JsonUtil.getMapper().readValue(persisted.getMetadata(), Map.class);
        assertFalse(metadata.containsKey("contextReceipt"));
    }
}
