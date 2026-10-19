package cn.jia.chat.service;

import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatTypedReviewContextStoreTest {
    final ChatTypedDeliberationStore.Scope scope=new ChatTypedDeliberationStore.Scope("0","owner","client","123",7);
    final CaptureJdbc jdbc=new CaptureJdbc();final ChatTypedReviewContextStore store=new ChatTypedReviewContextStore(jdbc);
    final ChatDeliveryRevisionContract.Basis basis=ChatDeliveryRevisionContractTest.basis();
    static class CaptureJdbc extends JdbcTemplate {
        String sql;Object[] args;int writes;int updateCount=1;List<String[]> records=new ArrayList<>();
        @Override public int update(String sql,Object...args){this.sql=sql;this.args=args;writes++;return updateCount;}
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object...args) {
            this.sql=sql;this.args=args;List<T> result=new ArrayList<>();
            try {for(var row:records) {ResultSet rs=mock(ResultSet.class);for(int i=0;i<row.length;i++)when(rs.getString(i+1)).thenReturn(row[i]);result.add(mapper.mapRow(rs,result.size()));}}
            catch(java.sql.SQLException failure){throw new AssertionError(failure);}return result;
        }
    }
    void record(String json,String sha,String message){jdbc.records=java.util.Collections.singletonList(new String[]{json,sha,message});}
    void recordBasis(){String json=ChatDeliveryRevisionContract.json(basis);record(json,ChatDeliveryReviewContract.sha(json),basis.sourceMessageId());}
    @Test void insertIsAnImmutableScopedWriteWithExplicitNoBatchAndNoUpsert() {
        store.insert(scope,"request",1,null,10);assertEquals(1,jdbc.writes);
        assertTrue(jdbc.sql.contains("INSERT INTO chat_typed_review_context"));assertFalse(jdbc.sql.contains("UPDATE"));
        assertArrayEquals(new Object[]{"0","owner","client","request",1L,"null",ChatDeliveryReviewContract.sha("null"),10L},jdbc.args);
    }
    @Test void actualBasisRequestCannotBeForgedIntoAnotherAdmission() {
        assertThrows(IllegalArgumentException.class,()->store.insert(scope,"other_request",1,basis,10));assertEquals(0,jdbc.writes);
    }
    @Test void readsAreScopedThroughPersistedRequestGenerationAndUserIdentityWithoutAnyWrite() {
        recordBasis();assertEquals(basis,store.require(scope,basis.sourceRequestId(),1));assertEquals(0,jdbc.writes);
        assertTrue(jdbc.sql.contains("JOIN chat_request"));assertTrue(jdbc.sql.contains("r.conversation_generation=?"));
        assertArrayEquals(new Object[]{"0","owner","client",basis.sourceRequestId(),1L,"123",7L},jdbc.args);
    }
    @Test void missingAndDuplicateRowsCannotMeanNoBatch() {
        assertThrows(IllegalStateException.class,()->store.require(scope,"request",1));recordBasis();jdbc.records=new ArrayList<>(jdbc.records);jdbc.records.add(jdbc.records.getFirst());
        assertThrows(IllegalStateException.class,()->store.require(scope,basis.sourceRequestId(),1));
    }
    @Test void hashAndOriginalUserMismatchAreFailClosed() {
        record("null","0".repeat(64),"100");assertThrows(IllegalStateException.class,()->store.require(scope,"request",1));
        String json=ChatDeliveryRevisionContract.json(basis);record(json,ChatDeliveryReviewContract.sha(json),"101");
        assertThrows(IllegalStateException.class,()->store.require(scope,basis.sourceRequestId(),1));
    }
    @Test void validHashDoesNotTurnInvalidJsonOrWrongSourceIntoAuthority() {
        record("{}",ChatDeliveryReviewContract.sha("{}"),"100");assertThrows(IllegalStateException.class,()->store.require(scope,"request",1));
        recordBasis();assertThrows(IllegalStateException.class,()->store.require(scope,"different_request",1));
    }
    @Test void explicitJsonNullIsReadOnlyNoBatchNotLazyInitialization() {
        record("null",ChatDeliveryReviewContract.sha("null"),"100");assertNull(store.require(scope,"request",1));assertEquals(0,jdbc.writes);
    }
    @Test void failedInsertAndBadRevisionDoNotPretendSuccess() {
        jdbc.updateCount=0;assertThrows(IllegalStateException.class,()->store.insert(scope,"request",1,null,10));
        assertThrows(IllegalArgumentException.class,()->store.insert(scope,"request",0,null,10));
    }
    @Test void insertMustJoinAdmissionTransaction() throws Exception {
        var method=ChatTypedReviewContextStore.class.getMethod("insert",ChatTypedDeliberationStore.Scope.class,String.class,long.class,ChatDeliveryRevisionContract.Basis.class,long.class);
        assertEquals(Propagation.MANDATORY,method.getAnnotation(Transactional.class).propagation());
        assertTrue(ChatTypedReviewContextStore.class.getMethod("require",ChatTypedDeliberationStore.Scope.class,String.class,long.class).getAnnotation(Transactional.class).readOnly());
    }
}
