package cn.jia.chat.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.sql.ResultSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatCompletedMessageSourceStoreTest {
    static final class Jdbc extends JdbcTemplate {
        String sql; Object[] args; int count=1;
        @Override public <T> List<T> query(String query,RowMapper<T> mapper,Object... parameters) {
            sql=query;args=parameters;
            try {
                ResultSet rs=mock(ResultSet.class);when(rs.getString(1)).thenReturn("original");when(rs.getString(2)).thenReturn("{}");
                when(rs.getString(3)).thenReturn("ASSISTANT");when(rs.getString(4)).thenReturn("agent");
                var value=mapper.mapRow(rs,0);return count==0?List.of():count==1?List.of(value):List.of(value,value);
            } catch(Exception failure){throw new AssertionError(failure);}
        }
    }
    @Test void actualSqlFencesOwnerClientTaskGenerationAndLiveConversationWithoutSelectingLatest() {
        Jdbc jdbc=new Jdbc();var store=new ChatCompletedMessageSourceStore(jdbc);
        assertEquals("original",store.find("0","owner","client","task","42",3,9).content());
        assertArrayEquals(new Object[]{"0","owner","client","42",9L,3L,"task","task:task"},jdbc.args);
        for(String fence:List.of("BINARY m.tenant_id=BINARY ?","BINARY m.jiacn=BINARY ?","BINARY m.client_id=BINARY ?",
                "BINARY m.conversation_id=BINARY ?","m.id=?","c.deleted_at IS NULL","c.lifecycle_generation=?",
                "BINARY c.task_id=BINARY ?","BINARY c.conversation_type=BINARY 'juyiting'",
                "BINARY c.conversation_scope_type=BINARY 'bounty'","BINARY c.conversation_scope_key=BINARY ?",
                "BINARY CAST(c.id AS CHAR)=BINARY m.conversation_id")) assertTrue(jdbc.sql.contains(fence),fence);
        assertFalse(jdbc.sql.contains("ORDER BY"));assertFalse(jdbc.sql.contains("LIMIT"));assertFalse(jdbc.sql.contains("FOR UPDATE"));
    }
    @Test void absentOrAmbiguousMessageRowsNeverProduceAByteSource() {
        Jdbc jdbc=new Jdbc();var store=new ChatCompletedMessageSourceStore(jdbc);
        jdbc.count=0;assertNull(store.find("0","owner","client","task","42",3,9));
        jdbc.count=2;assertNull(store.find("0","owner","client","task","42",3,9));
    }
}
