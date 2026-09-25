package cn.jia.chat.service;

import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.ChatContextSnapshotEntity;
import cn.jia.chat.deliberation.ChatConversationEventEntity;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatDeliberationStates;
import cn.jia.chat.deliberation.ChatDispatchOutboxEntity;
import cn.jia.chat.deliberation.ChatRequestEntity;
import cn.jia.chat.deliberation.ChatTurnEntity;
import cn.jia.chat.deliberation.InteractionRoute;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatDeliberationServiceTest {
    private final InMemoryDao dao = new InMemoryDao();
    private final ChatConversationDao conversations = mock(ChatConversationDao.class);
    private final ChatMessageDao messages = mock(ChatMessageDao.class);
    private final AgentService agents = mock(AgentService.class);
    private final AtomicLong messageIds = new AtomicLong(900);
    private ChatConversationEntity conversation;
    private ChatDeliberationService service;

    @BeforeEach
    void setUp() {
        conversation = new ChatConversationEntity()
                .setId(42L).setJiacn("owner-a").setConversationType("juyiting")
                .setConversationScopeType("public").setConversationScopeKey("public")
                .setTargetAgentIds("[\"agent-a\",\"agent-b\"]").setLifecycleGeneration(3L);
        conversation.setTenantId("0");
        conversation.setClientId("client-a");
        when(conversations.lockScopedById("owner-a", "client-a", "42")).thenAnswer(i -> conversation);
        when(conversations.findScopedById("owner-a", "client-a", "42")).thenAnswer(i -> conversation);
        when(messages.findOwnedByConversationIdWithLimit("owner-a", "client-a", "42", 200))
                .thenReturn(List.of());
        when(messages.insertScoped(anyString(), anyString(), any(ChatMessageEntity.class))).thenAnswer(invocation -> {
            ChatMessageEntity entity = invocation.getArgument(2);
            if (entity.getId() == null) entity.setId(messageIds.incrementAndGet());
            return 1;
        });
        service = new ChatDeliberationService(dao, conversations, messages, agents);
    }

    @Test
    void groupAdmissionCreatesOneUserRequestAndIndependentTurnsSnapshotsAndOutbox() {
        ChatDeliberationService.Admission admission = admit("req-group", "hello");

        assertEquals(2, admission.dispatches().size());
        assertNotEquals(admission.dispatches().get(0).turnId(), admission.dispatches().get(1).turnId());
        assertNotEquals(admission.dispatches().get(0).contextSnapshotId(),
                admission.dispatches().get(1).contextSnapshotId());
        assertEquals(1, dao.requests.size());
        assertEquals(2, dao.turns.size());
        assertEquals(2, dao.snapshots.size());
        assertEquals(2, dao.outboxes.values().stream()
                .filter(outbox -> "DISPATCH".equals(outbox.getEventType())).count());
        assertTrue(admission.dispatches().stream().allMatch(dispatch ->
                dispatch.factsManifest().get("targetAgentId").equals(dispatch.targetAgentId())));
    }

    @Test
    void sameIdempotencyBodyReplaysButConflictingBodyFailsClosed() {
        ChatDeliberationService.Admission first = admit("req-idem", "same");
        ChatDeliberationService.Admission replay = admit("req-idem", "same");
        assertTrue(replay.replay());
        assertEquals(first.userMessageId(), replay.userMessageId());
        assertEquals(1, dao.requests.size());

        ChatDeliberationException conflict = assertThrows(ChatDeliberationException.class,
                () -> admit("req-idem", "different"));
        assertEquals(ChatDeliberationException.Reason.CONFLICT, conflict.reason());
    }

    @Test
    void generationAndOwnerVisibilityFailClosed() {
        conversation.setLifecycleGeneration(4L);
        ChatDeliberationException stale = assertThrows(ChatDeliberationException.class,
                () -> admit("req-stale", "hello"));
        assertEquals(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN, stale.reason());

        conversation.setLifecycleGeneration(3L);
        ChatDeliberationService.Admission admitted = admit("req-visible", "hello");
        ChatDeliberationException foreign = assertThrows(ChatDeliberationException.class,
                () -> service.getTurn("0", "owner-b", "client-a", admitted.dispatches().getFirst().turnId()));
        assertEquals(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN, foreign.reason());
    }

    @Test
    void inspectRejectsUntrustedReference() {
        ChatMessageDTO request = request("req-inspect", "inspect");
        request.setInteractionHint("inspect");
        request.setInputRefs(List.of(Map.of("type", "task", "id", "foreign-task")));
        ChatDeliberationException denied = assertThrows(ChatDeliberationException.class,
                () -> service.admit("0", "owner-a", "client-a", "42", 3L, scope(),
                        InteractionRoute.INSPECT, request));
        assertEquals(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN, denied.reason());
    }

    @Test
    void deltaDuplicateAndGapAreNotPublishedAndFinalIsIdempotent() {
        ChatDeliberationService.Admission admission = admit("req-final", "hello");
        ChatDeliberationService.Dispatch dispatch = admission.dispatches().getFirst();

        var accepted = service.acceptDelta("0", "owner-a", "client-a", "42", 3L, dispatch.targetAgentId(),
                admission.requestId(), dispatch.turnId(), dispatch.dispatchId(),
                dispatch.contextSnapshotId(), dispatch.contextHash(), 1L, "A");
        assertEquals(ChatDeliberationService.DeltaStatus.ACCEPTED, accepted.status());
        assertEquals(ChatDeliberationService.DeltaStatus.DUPLICATE,
                service.acceptDelta("0", "owner-a", "client-a", "42", 3L, dispatch.targetAgentId(),
                        admission.requestId(), dispatch.turnId(), dispatch.dispatchId(),
                        dispatch.contextSnapshotId(), dispatch.contextHash(), 1L, "A").status());
        assertEquals(ChatDeliberationService.DeltaStatus.GAP,
                service.acceptDelta("0", "owner-a", "client-a", "42", 3L, dispatch.targetAgentId(),
                        admission.requestId(), dispatch.turnId(), dispatch.dispatchId(),
                        dispatch.contextSnapshotId(), dispatch.contextHash(), 3L, "C").status());

        var first = service.persistFinal("0", "owner-a", "client-a", "42", 3L, dispatch.targetAgentId(),
                admission.requestId(), dispatch.turnId(), dispatch.dispatchId(),
                dispatch.contextSnapshotId(), dispatch.contextHash(), "final", "Agent A");
        var replay = service.persistFinal("0", "owner-a", "client-a", "42", 3L, dispatch.targetAgentId(),
                admission.requestId(), dispatch.turnId(), dispatch.dispatchId(),
                dispatch.contextSnapshotId(), dispatch.contextHash(), "final", "Agent A");
        assertEquals(ChatDeliberationService.FinalStatus.PERSISTED, first.status());
        assertEquals(ChatDeliberationService.FinalStatus.DUPLICATE, replay.status());
        assertEquals(first.messageId(), replay.messageId());
        assertEquals(1, dao.outboxes.values().stream()
                .filter(outbox -> "FINAL_PERSISTED".equals(outbox.getEventType())).count());
    }

    @Test
    void cancellingOneGroupTurnDoesNotCancelSiblingOrAnyCommandState() {
        ChatDeliberationService.Admission admission = admit("req-cancel", "hello");
        String first = admission.dispatches().get(0).turnId();
        String second = admission.dispatches().get(1).turnId();
        service.cancelTurn("0", "owner-a", "client-a", first, 0L, "test cancel");
        assertEquals(ChatDeliberationStates.CANCELLED, dao.turns.get(first).getState());
        assertEquals(ChatDeliberationStates.RECEIVED, dao.turns.get(second).getState());
        String cancelPayload = dao.lockOutbox("0", "owner-a", "client-a", first, "CANCEL_REQUESTED")
                .getPayloadJson();
        assertTrue(cancelPayload.contains("\"tenantId\":\"0\""));
        assertTrue(cancelPayload.contains("\"ownerJiacn\":\"owner-a\""));
        assertTrue(cancelPayload.contains("\"clientId\":\"client-a\""));
        assertTrue(cancelPayload.contains("\"targetAgentId\":"));
        assertTrue(cancelPayload.contains("\"conversationGeneration\":\"3\""));
    }

    @Test
    void cancelAndFinalTerminalOrderNeverOverwritesWinner() {
        var finalFirst = admit("req-final-first", "hello");
        var finalDispatch = finalFirst.dispatches().getFirst();
        service.persistFinal("0", "owner-a", "client-a", "42", 3L, finalDispatch.targetAgentId(),
                finalFirst.requestId(), finalDispatch.turnId(), finalDispatch.dispatchId(),
                finalDispatch.contextSnapshotId(), finalDispatch.contextHash(), "done", "Agent A");
        service.cancelTurn("0", "owner-a", "client-a", finalDispatch.turnId(), null, "late cancel");
        assertEquals(ChatDeliberationStates.FINAL_PERSISTED, dao.turns.get(finalDispatch.turnId()).getState());

        var cancelFirst = admit("req-cancel-first", "hello");
        var cancelDispatch = cancelFirst.dispatches().getFirst();
        service.cancelTurn("0", "owner-a", "client-a", cancelDispatch.turnId(), 0L, "cancel wins");
        assertThrows(ChatDeliberationException.class, () -> service.persistFinal(
                "0", "owner-a", "client-a", "42", 3L, cancelDispatch.targetAgentId(),
                cancelFirst.requestId(), cancelDispatch.turnId(), cancelDispatch.dispatchId(),
                cancelDispatch.contextSnapshotId(), cancelDispatch.contextHash(), "too late", "Agent A"));
        assertEquals(ChatDeliberationStates.CANCELLED, dao.turns.get(cancelDispatch.turnId()).getState());
    }

    @Test
    void callbackRequiresExactTenantConversationAndGeneration() {
        var admission = admit("req-binding", "hello");
        var dispatch = admission.dispatches().getFirst();
        assertThrows(ChatDeliberationException.class, () -> service.acceptDelta(
                "tenant-b", "owner-a", "client-a", "42", 3L, dispatch.targetAgentId(),
                admission.requestId(), dispatch.turnId(), dispatch.dispatchId(),
                dispatch.contextSnapshotId(), dispatch.contextHash(), 1L, "x"));
        assertThrows(ChatDeliberationException.class, () -> service.acceptDelta(
                "0", "owner-a", "client-a", "43", 3L, dispatch.targetAgentId(),
                admission.requestId(), dispatch.turnId(), dispatch.dispatchId(),
                dispatch.contextSnapshotId(), dispatch.contextHash(), 1L, "x"));
        assertThrows(ChatDeliberationException.class, () -> service.acceptDelta(
                "0", "owner-a", "client-a", "42", 4L, dispatch.targetAgentId(),
                admission.requestId(), dispatch.turnId(), dispatch.dispatchId(),
                dispatch.contextSnapshotId(), dispatch.contextHash(), 1L, "x"));
    }

    @Test
    void gapPersistsRecoveryStateAndDispatchMarkIsIdempotent() {
        var admission = admit("req-gap", "hello");
        var dispatch = admission.dispatches().getFirst();
        assertEquals(ChatDeliberationService.DeltaStatus.GAP, service.acceptDelta(
                "0", "owner-a", "client-a", "42", 3L, dispatch.targetAgentId(),
                admission.requestId(), dispatch.turnId(), dispatch.dispatchId(),
                dispatch.contextSnapshotId(), dispatch.contextHash(), 2L, "gap").status());
        assertEquals(ChatDeliberationStates.RECOVERY_REQUIRED, dao.turns.get(dispatch.turnId()).getState());
        service.markDispatch("0", "owner-a", "client-a", dispatch.turnId(), true);
        service.markDispatch("0", "owner-a", "client-a", dispatch.turnId(), true);
        assertEquals("READY", dao.lockOutbox("0", "owner-a", "client-a", dispatch.turnId(), "DISPATCH").getStatus());
    }


    @Test
    void mixedBuiltinAndHostedGroupCreatesIndependentDurableDispatches() throws Exception {
        conversation.setTargetAgentIds("[\"builtin-songjiang\",\"hosted-a\"]");
        ChatMessageDTO request = request("req-mixed", "council");
        request.setTargetAgentIds(List.of("builtin-songjiang", "hosted-a"));
        JuyitingConversationScope mixed = new JuyitingConversationScope("public", "public", null,
                "builtin-songjiang", List.of("builtin-songjiang", "hosted-a"),
                List.of("builtin-songjiang", "hosted-a"));
        var admission = service.admit("0", "owner-a", "client-a", "42", 3L, mixed,
                InteractionRoute.CHAT, request);
        assertEquals(List.of("builtin-songjiang", "hosted-a"), admission.dispatches().stream()
                .map(ChatDeliberationService.Dispatch::targetAgentId).toList());
        for (var dispatch : admission.dispatches()) {
            ChatDispatchOutboxEntity event = dao.lockOutbox("0", "owner-a", "client-a",
                    dispatch.turnId(), "DISPATCH");
            assertTrue(event.getPayloadJson().contains("\"targetAgentId\":\"" + dispatch.targetAgentId() + "\""));
        }
    }

    @Test
    void durableViewsAndEventsExposeBigintsAsDecimalStrings() {
        var admission = admit("req-long", "hello");
        ChatRequestEntity request = dao.requests.get("req-long");
        request.setStateVersion(Long.MAX_VALUE - 1);
        ChatTurnEntity turn = dao.turns.get(admission.dispatches().getFirst().turnId());
        turn.setStateVersion(Long.MAX_VALUE - 2).setLastDeltaSeq(Long.MAX_VALUE - 3);
        var view = service.getRequest("0", "owner-a", "client-a", "req-long");
        assertEquals(Long.toString(Long.MAX_VALUE - 1), view.stateVersion());
        assertEquals(Long.toString(Long.MAX_VALUE - 2), view.turns().getFirst().stateVersion());
        assertEquals(Long.toString(Long.MAX_VALUE - 3), view.turns().getFirst().lastDeltaSeq());
    }
    private ChatDeliberationService.Admission admit(String requestId, String content) {
        return service.admit("0", "owner-a", "client-a", "42", 3L, scope(),
                InteractionRoute.CHAT, request(requestId, content));
    }

    private ChatMessageDTO request(String requestId, String content) {
        ChatMessageDTO request = new ChatMessageDTO();
        request.setRequestId(requestId);
        request.setRequestRevision(1L);
        request.setContent(content);
        request.setConversationType("juyiting");
        request.setConversationScopeType("public");
        request.setConversationScopeKey("public");
        request.setTargetAgentIds(List.of("agent-a", "agent-b"));
        return request;
    }

    private JuyitingConversationScope scope() {
        return new JuyitingConversationScope("public", "public", null, "agent-a",
                List.of("agent-a", "agent-b"), List.of("agent-a", "agent-b"));
    }

    private static final class InMemoryDao implements ChatDeliberationDao {
        private final Map<String, ChatRequestEntity> requests = new LinkedHashMap<>();
        private final Map<String, ChatTurnEntity> turns = new LinkedHashMap<>();
        private final Map<String, ChatContextSnapshotEntity> snapshots = new LinkedHashMap<>();
        private final Map<String, ChatDispatchOutboxEntity> outboxes = new LinkedHashMap<>();
        private final List<ChatConversationEventEntity> events = new ArrayList<>();
        private long requestPk;
        private long eventPk;

        public int insertSnapshot(ChatContextSnapshotEntity e) { snapshots.put(e.getSnapshotId(), e); return 1; }
        public int insertRequest(ChatRequestEntity e) { e.setId(++requestPk); requests.put(e.getRequestId(), e); return 1; }
        public int insertTurn(ChatTurnEntity e) { turns.put(e.getTurnId(), e); return 1; }
        public int insertOutbox(ChatDispatchOutboxEntity e) { outboxes.put(e.getEventId(), e); return 1; }
        public int insertEvent(ChatConversationEventEntity e) { e.setEventSequence(++eventPk); events.add(e); return 1; }
        public int assignEventVersion(long sequence) { events.stream().filter(e->e.getEventSequence()==sequence).findFirst().orElseThrow().setEventVersion(sequence); return 1; }
        public ChatRequestEntity lockRequest(String t,String o,String c,String r,long v){
            ChatRequestEntity found=requests.get(r); return found!=null && t.equals(found.getTenantId()) && o.equals(found.getOwnerJiacn()) && c.equals(found.getClientId()) && found.getRequestRevision()==v ? found:null; }
        public ChatRequestEntity findRequest(String t,String o,String c,String r){ return scoped(requests.get(r),t,o,c); }
        public List<ChatTurnEntity> findTurnsByRequest(String t,String o,String c,String r){
            return turns.values().stream().filter(x->x.getRequestId().equals(r)&&x.getTenantId().equals(t)
                    &&x.getOwnerJiacn().equals(o)&&x.getClientId().equals(c)).toList(); }
        public ChatTurnEntity findTurn(String t,String o,String c,String id){ return scoped(turns.get(id),t,o,c); }
        public ChatTurnEntity lockTurn(String t,String o,String c,String id){ return scoped(turns.get(id),t,o,c); }
        public ChatContextSnapshotEntity findSnapshot(String t,String o,String c,String id){
            ChatContextSnapshotEntity x=snapshots.get(id);
            return x!=null&&t.equals(x.getTenantId())&&o.equals(x.getOwnerJiacn())&&c.equals(x.getClientId())?x:null;
        }
        public ChatDispatchOutboxEntity lockOutbox(String t,String o,String c,String turn,String event){
            return outboxes.values().stream().filter(x->t.equals(x.getTenantId())&&o.equals(x.getOwnerJiacn())
                    &&c.equals(x.getClientId())&&turn.equals(x.getTurnId())&&event.equals(x.getEventType())).findFirst().orElse(null);
        }
        public ChatDispatchOutboxEntity lockOutboxById(String t,String o,String c,String id){ return outboxes.get(id); }
        public ChatDispatchOutboxEntity lockOutboxByDispatch(String t,String o,String c,String id){ return outboxes.values().stream().filter(x->id.equals(x.getDispatchId())&&"DISPATCH".equals(x.getEventType())).findFirst().orElse(null); }
        public List<ChatDispatchOutboxEntity> findDueOutbox(long now,int limit){ return outboxes.values().stream().filter(x->x.getAvailableAt()!=null&&x.getAvailableAt()<=now).limit(limit).toList(); }
        public List<ChatConversationEventEntity> replayEvents(String t,String o,String c,String id,long g,long after,long through,int limit){ return events.stream().filter(e->t.equals(e.getTenantId())&&o.equals(e.getOwnerJiacn())&&c.equals(e.getClientId())&&id.equals(e.getConversationId())&&g==e.getConversationGeneration()&&e.getEventSequence()>after&&e.getEventSequence()<=through).limit(limit).toList(); }
        public long eventHighWatermark(String t,String o,String c,String id,long g){ return events.stream().filter(e->t.equals(e.getTenantId())&&o.equals(e.getOwnerJiacn())&&c.equals(e.getClientId())&&id.equals(e.getConversationId())&&g==e.getConversationGeneration()).mapToLong(ChatConversationEventEntity::getEventSequence).max().orElse(0L); }
        public int acceptDelta(ChatTurnEntity turn,long seq,String digest,long now){ return 1; }
        public int persistFinal(ChatTurnEntity turn,String digest,long message,long now){ return 1; }
        public int updateTurnState(ChatTurnEntity turn,String state,String reason,long now){ return 1; }
        public int publishFinal(ChatTurnEntity turn,long now){ return 1; }
        public int updateOutbox(ChatDispatchOutboxEntity outbox,String status,long now){
            outbox.setStatus(status).setVersion(outbox.getVersion()+1).setUpdatedAt(now); return 1; }
        public int claimOutbox(ChatDispatchOutboxEntity row,String owner,long until,long fence,long now){ row.setStatus("CLAIMED").setLeaseOwner(owner).setLeaseUntil(until).setFencingToken(fence).setVersion(row.getVersion()+1); return 1; }
        public int renewOutbox(ChatDispatchOutboxEntity row,long until,long now){ row.setLeaseUntil(until).setVersion(row.getVersion()+1); return 1; }
        public int acknowledgeOutbox(ChatDispatchOutboxEntity row,long now){ row.setStatus("SENT").setVersion(row.getVersion()+1); return 1; }
        public int settleOutbox(ChatDispatchOutboxEntity row,String status,Long available,String error,Long sent,long now){ row.setStatus(status).setAvailableAt(available).setLastError(error).setSentAt(sent).setVersion(row.getVersion()+1); return 1; }
        public int updateRequestState(ChatRequestEntity request,String state,long now){ request.setAggregateState(state); return 1; }
        private ChatRequestEntity scoped(ChatRequestEntity value,String t,String o,String c){
            return value!=null&&value.getTenantId().equals(t)&&value.getOwnerJiacn().equals(o)&&value.getClientId().equals(c)?value:null; }
        private ChatTurnEntity scoped(ChatTurnEntity value,String t,String o,String c){
            return value!=null&&value.getTenantId().equals(t)&&value.getOwnerJiacn().equals(o)&&value.getClientId().equals(c)?value:null; }
    }
}
