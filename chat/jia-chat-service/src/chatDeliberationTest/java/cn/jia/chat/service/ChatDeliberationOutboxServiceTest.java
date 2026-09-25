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

    private ChatDispatchOutboxEntity row() {
        return new ChatDispatchOutboxEntity().setEventId("evt-1").setTenantId("tenant-a")
                .setOwnerJiacn("owner-a").setClientId("client-a").setTurnId("turn-1")
                .setDispatchId("dispatch-1").setEventType("DISPATCH").setPayloadJson("{}")
                .setAvailableAt(1L).setAttemptCount(0).setFencingToken(0L).setVersion(0L)
                .setCreatedAt(1L).setUpdatedAt(1L);
    }

    private static final class FakeDao implements ChatDeliberationDao {
        ChatDispatchOutboxEntity row;
        public List<ChatDispatchOutboxEntity> findDueOutbox(long now,int limit){return List.of(row);}
        public ChatDispatchOutboxEntity lockOutboxById(String t,String o,String c,String id){return row;}
        public int claimOutbox(ChatDispatchOutboxEntity ignored,String owner,long until,long fence,long now){
            row.setStatus("CLAIMED").setLeaseOwner(owner).setLeaseUntil(until).setFencingToken(fence)
                    .setAttemptCount(row.getAttemptCount()+1).setVersion(row.getVersion()+1).setUpdatedAt(now); return 1; }
        public int settleOutbox(ChatDispatchOutboxEntity ignored,String status,Long available,String error,Long sent,long now){
            row.setStatus(status).setAvailableAt(available).setLastError(error).setSentAt(sent)
                    .setLeaseOwner(null).setLeaseUntil(null).setVersion(row.getVersion()+1).setUpdatedAt(now); return 1; }
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
        public List<ChatConversationEventEntity> replayEvents(String a,String b,String c,String d,long e,long f,int g){return List.of();}
        public int acceptDelta(ChatTurnEntity a,long b,String c,long d){return 0;}
        public int persistFinal(ChatTurnEntity a,String b,long c,long d){return 0;}
        public int updateTurnState(ChatTurnEntity a,String b,String c,long d){return 0;}
        public int publishFinal(ChatTurnEntity a,long b){return 0;}
        public int updateOutbox(ChatDispatchOutboxEntity a,String b,long c){return 0;}
        public int updateRequestState(ChatRequestEntity a,String b,long c){return 0;}
    }
}
