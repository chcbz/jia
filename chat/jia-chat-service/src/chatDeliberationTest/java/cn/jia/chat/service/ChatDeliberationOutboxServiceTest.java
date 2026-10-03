package cn.jia.chat.service;

import cn.jia.chat.deliberation.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatDeliberationOutboxServiceTest {
    @Test
    void staleLeaseIsRecoveredWithHigherFenceAndBackoffThenSettledOnce() {
        FakeDao dao = new FakeDao();
        dao.row = row().setStatus("CLAIMED").setLeaseOwner("dead-worker").setLeaseUntil(99L)
                .setAttemptCount(1).setFencingToken(7L).setVersion(4L);
        ChatDeliberationOutboxService service = new ChatDeliberationOutboxService(dao);
        var first = service.claim(dao.row, "worker-b", 100L, 50L);
        assertNotNull(first);
        assertTrue(first.recoveredStaleLease());
        assertEquals(8L, first.row().getFencingToken());
        assertTrue(service.retry(first, "socket failed", 110L));
        assertEquals("RETRY", dao.row.getStatus());
        assertTrue(dao.row.getAvailableAt() > 110L);

        long retryAt = dao.row.getAvailableAt();
        var second = service.claim(dao.row, "worker-c", retryAt, 50L);
        assertNotNull(second);
        assertFalse(second.recoveredStaleLease());
        assertEquals(9L, second.row().getFencingToken());
        assertTrue(service.sent(second, retryAt + 1));
        assertEquals("SENT", dao.row.getStatus());
        assertNull(service.claim(dao.row, "worker-d", retryAt + 2, 50L));
    }


    @Test
    void actionAdmissionRequiresTheCurrentUnexpiredFenceAndRejectsSettledClaims() {
        FakeDao dao=new FakeDao(); dao.row=row().setStatus("READY");
        var service=new ChatDeliberationOutboxService(dao);
        var claim=service.claim(dao.row,"action-owner",100L,50L);
        assertNotNull(service.requireActiveClaim(claim,101L));
        assertThrows(IllegalStateException.class,()->service.requireActiveClaim(claim,150L));
        assertTrue(service.sent(claim,151L));
        assertThrows(IllegalStateException.class,()->service.requireActiveClaim(claim,152L));
    }

    @Test
    void heartbeatExtendsLeaseAndPreventsSecondInstanceFromStealingLongCall() {
        FakeDao dao = new FakeDao();
        dao.row = row().setStatus("READY");
        ChatDeliberationOutboxService firstWorker = new ChatDeliberationOutboxService(dao);
        ChatDeliberationOutboxService secondWorker = new ChatDeliberationOutboxService(dao);
        var claim = firstWorker.claim(dao.row, "worker-a", 100L, 50L);
        assertNotNull(claim);
        assertTrue(firstWorker.renew(claim, 140L, 50L));
        assertEquals(190L, dao.row.getLeaseUntil());
        assertNull(secondWorker.claim(dao.row, "worker-b", 151L, 50L));
        var recovered = secondWorker.claim(dao.row, "worker-b", 191L, 50L);
        assertNotNull(recovered);
        assertTrue(recovered.recoveredStaleLease());
        assertFalse(firstWorker.renew(claim, 192L, 50L));
    }

    @Test
    void stableDispatchAcknowledgementIsIdempotentAndRejectsWrongAgentOrMessage() {
        FakeDao dao = new FakeDao();
        dao.row = row().setStatus("AWAITING_ACK")
                .setPayloadJson(cn.jia.core.util.JsonUtil.toJson(java.util.Map.of("targetAgentId", "agent-a")));
        ChatDeliberationOutboxService service = new ChatDeliberationOutboxService(dao);
        assertFalse(service.acknowledgeHostedDispatch("tenant-a","owner-a","client-a",
                "agent-b","dispatch-1","evt-1",100L));
        assertFalse(service.acknowledgeHostedDispatch("tenant-a","owner-a","client-a",
                "agent-a","dispatch-1","wrong",100L));
        assertTrue(service.acknowledgeHostedDispatch("tenant-a","owner-a","client-a",
                "agent-a","dispatch-1","evt-1",100L));
        assertTrue(service.acknowledgeHostedDispatch("tenant-a","owner-a","client-a",
                "agent-a","dispatch-1","evt-1",101L));
    }
    private ChatDispatchOutboxEntity row() {
        return new ChatDispatchOutboxEntity().setEventId("evt-1").setTenantId("tenant-a")
                .setOwnerJiacn("owner-a").setClientId("client-a").setTurnId("turn-1")
                .setDispatchId("dispatch-1").setEventType("DISPATCH").setPayloadJson("{}")
                .setAvailableAt(1L).setAttemptCount(0).setFencingToken(0L).setVersion(0L)
                .setCreatedAt(1L).setUpdatedAt(1L);
    }

    private static final class FakeDao implements ChatDeliberationDao {
        ChatDispatchOutboxEntity row;
        public List<ChatDispatchOutboxEntity> findDueOutbox(long now,int limit){return List.of(copy(row));}
        public ChatDispatchOutboxEntity lockOutboxById(String t,String o,String c,String id){return copy(row);}
        public ChatDispatchOutboxEntity lockOutboxByDispatch(String t,String o,String c,String id){return copy(row);}
        public int claimOutbox(ChatDispatchOutboxEntity expected,String owner,long until,long fence,long now){
            if (!row.getVersion().equals(expected.getVersion())) return 0;
            row.setStatus("CLAIMED").setLeaseOwner(owner).setLeaseUntil(until).setFencingToken(fence)
                    .setAttemptCount(row.getAttemptCount()+1).setVersion(row.getVersion()+1).setUpdatedAt(now); return 1; }
        public int renewOutbox(ChatDispatchOutboxEntity expected,long until,long now){
            if (!"CLAIMED".equals(row.getStatus()) || !row.getVersion().equals(expected.getVersion())
                    || !row.getLeaseOwner().equals(expected.getLeaseOwner())
                    || !row.getFencingToken().equals(expected.getFencingToken())) return 0;
            row.setLeaseUntil(until).setVersion(row.getVersion()+1).setUpdatedAt(now); return 1; }
        public int acknowledgeOutbox(ChatDispatchOutboxEntity expected,long now){
            if (!row.getVersion().equals(expected.getVersion())) return 0;
            row.setStatus("SENT").setVersion(row.getVersion()+1).setUpdatedAt(now); return 1; }
        public int settleOutbox(ChatDispatchOutboxEntity expected,String status,Long available,String error,Long sent,long now){
            if (!row.getVersion().equals(expected.getVersion())) return 0;
            row.setStatus(status).setAvailableAt(available).setLastError(error).setSentAt(sent)
                    .setLeaseOwner(null).setLeaseUntil(null).setVersion(row.getVersion()+1).setUpdatedAt(now); return 1; }
        private ChatDispatchOutboxEntity copy(ChatDispatchOutboxEntity x){
            if(x==null)return null; return new ChatDispatchOutboxEntity().setEventId(x.getEventId()).setTenantId(x.getTenantId())
                    .setOwnerJiacn(x.getOwnerJiacn()).setClientId(x.getClientId()).setTurnId(x.getTurnId())
                    .setDispatchId(x.getDispatchId()).setEventType(x.getEventType()).setStatus(x.getStatus())
                    .setPayloadJson(x.getPayloadJson()).setVersion(x.getVersion()).setAvailableAt(x.getAvailableAt())
                    .setLeaseOwner(x.getLeaseOwner()).setLeaseUntil(x.getLeaseUntil()).setAttemptCount(x.getAttemptCount())
                    .setFencingToken(x.getFencingToken()).setLastError(x.getLastError()).setSentAt(x.getSentAt())
                    .setCreatedAt(x.getCreatedAt()).setUpdatedAt(x.getUpdatedAt()); }
        public int insertSnapshot(ChatContextSnapshotEntity e){throw new UnsupportedOperationException();}
        public int insertRequest(ChatRequestEntity e){throw new UnsupportedOperationException();}
        public int insertTurn(ChatTurnEntity e){throw new UnsupportedOperationException();}
        public int insertOutbox(ChatDispatchOutboxEntity e){throw new UnsupportedOperationException();}
        public int insertEvent(ChatConversationEventEntity e){throw new UnsupportedOperationException();}
        public int assignEventVersion(long e){throw new UnsupportedOperationException();}
        public ChatRequestEntity lockRequest(String a,String b,String c,String d,long e){return null;}
        public ChatRequestEntity findRequest(String a,String b,String c,String d){return null;}
        public List<ChatTurnEntity> findTurnsByRequest(String a,String b,String c,String d){return List.of();}
        public ChatTurnEntity findTurn(String a,String b,String c,String d){return null;}
        public ChatTurnEntity lockTurn(String a,String b,String c,String d){return null;}
        public ChatContextSnapshotEntity findSnapshot(String a,String b,String c,String d){return null;}
        public ChatDispatchOutboxEntity lockOutbox(String a,String b,String c,String d,String e){return null;}
        public List<ChatConversationEventEntity> replayEvents(String a,String b,String c,String d,long e,long f,long through,int g){return List.of();}
        public long eventHighWatermark(String a,String b,String c,String d,long e){return 0L;}
        public int acceptDelta(ChatTurnEntity a,long b,String c,long d){return 0;}
        public int persistFinal(ChatTurnEntity a,String b,long c,long d){return 0;}
        public int updateTurnState(ChatTurnEntity a,String b,String c,long d){return 0;}
        public int publishFinal(ChatTurnEntity a,long b){return 0;}
        public int updateOutbox(ChatDispatchOutboxEntity a,String b,long c){return 0;}
        public int updateRequestState(ChatRequestEntity a,String b,long c){return 0;}
    }
}
