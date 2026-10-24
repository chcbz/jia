package cn.jia.chat.dao;

import cn.jia.chat.dao.impl.ChatMessageDaoImpl;
import cn.jia.chat.mapper.ChatMessageMapper;
import cn.jia.common.dao.BaseDaoImpl;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ChatMessageMemorySnapshotScopeTest {
    @Test
    void existingAreaZeroSnapshotKeepsExactIdentityAndSnapshotArguments() throws Exception {
        ChatMessageMapper mapper = mock(ChatMessageMapper.class);
        ChatMessageDaoImpl dao = dao(mapper);
        when(mapper.markMemorySnapshotSynced("owner-a", "client-a", "12", 12L, List.of(7L)))
                .thenReturn(1);
        assertEquals(1, dao.markMemorySnapshotSynced("owner-a", "client-a", "12", List.of(7L)));
        verify(mapper).markMemorySnapshotSynced("owner-a", "client-a", "12", 12L, List.of(7L));

        String sql = String.join(" ", ChatMessageMapper.class.getMethod(
                "markMemorySnapshotSynced", String.class, String.class, String.class,
                long.class, List.class).getAnnotation(Update.class).value());
        assertTrue(sql.contains("c.tenant_id = '0'"), sql);
        assertTrue(sql.contains("m.tenant_id = c.tenant_id"), sql);
        assertTrue(sql.contains("CAST(m.tenant_id AS BINARY) = CAST(c.tenant_id AS BINARY)"), sql);
    }

    @Test
    void malformedIdentityOrSnapshotIsRejectedBeforeMapperAccess() throws Exception {
        ChatMessageMapper mapper = mock(ChatMessageMapper.class);
        ChatMessageDaoImpl dao = dao(mapper);
        assertThrows(IllegalArgumentException.class,
                () -> dao.markMemorySnapshotSynced(null, "client-a", "12", List.of(7L)));
        assertThrows(IllegalArgumentException.class,
                () -> dao.markMemorySnapshotSynced("owner-a", "client-a ", "12", List.of(7L)));
        assertThrows(IllegalArgumentException.class,
                () -> dao.markMemorySnapshotSynced("owner-a", "client-a", "012", List.of(7L)));
        assertThrows(IllegalArgumentException.class,
                () -> dao.markMemorySnapshotSynced("owner-a", "client-a", "12", List.of()));
        verifyNoInteractions(mapper);
    }

    private ChatMessageDaoImpl dao(ChatMessageMapper mapper) throws Exception {
        ChatMessageDaoImpl dao = new ChatMessageDaoImpl();
        var field = BaseDaoImpl.class.getDeclaredField("baseMapper");
        field.setAccessible(true);
        field.set(dao, mapper);
        return dao;
    }
}
