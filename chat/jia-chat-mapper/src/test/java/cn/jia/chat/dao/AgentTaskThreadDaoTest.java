package cn.jia.chat.dao;

import cn.jia.chat.dao.impl.AgentTaskThreadDaoImpl;
import cn.jia.chat.dao.impl.ChatConversationDaoImpl;
import cn.jia.chat.dao.impl.ChatMessageDaoImpl;
import cn.jia.chat.entity.AgentTaskThreadEntity;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.mapper.AgentTaskThreadMapper;
import cn.jia.chat.mapper.ChatConversationMapper;
import cn.jia.chat.mapper.ChatMessageMapper;
import cn.jia.common.dao.BaseDaoImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskThreadDaoTest {
    @BeforeAll
    static void initializeMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "b07-dao-test");
        for (Class<?> type : List.of(
                AgentTaskThreadEntity.class, ChatConversationEntity.class, ChatMessageEntity.class)) {
            TableInfoHelper.initTableInfo(assistant, type);
        }
    }

    @Test
    void bindingReadsUseByteExactScopedMapperQueries() {
        AgentTaskThreadMapper mapper = mock(AgentTaskThreadMapper.class);
        AgentTaskThreadDao dao = new AgentTaskThreadDaoImpl(mapper);

        dao.findByTaskThread("tenant-a", "client-a", "task-1", "team", "team");
        dao.findByTaskThreadForUpdate("tenant-a", "client-a", "task-1", "team", "team");
        dao.findByConversationId("tenant-a", "client-a", "42");
        dao.findAnyByConversationId("42");

        verify(mapper).findExactByTaskThread("tenant-a", "client-a", "task-1", "team", "team");
        verify(mapper).findExactByTaskThreadForUpdate("tenant-a", "client-a", "task-1", "team", "team");
        verify(mapper).findExactByConversationId("tenant-a", "client-a", "42");
        verify(mapper).findAnyExactByConversationId("42");
    }

    @Test
    void taskThreadMapperSqlNeverUsesPublicTenantFallback() {
        for (Method method : AgentTaskThreadMapper.class.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            if (select != null) {
                String sql = normalize(String.join(" ", select.value()));
                assertFalse(sql.contains("tenant_id = '0'"), method.getName() + ": " + sql);
                assertFalse(sql.contains("or tenant_id = '0'"), method.getName() + ": " + sql);
            }
        }
    }

    @Test
    void scopedMessageWriteOverridesCallerScopeAndReadOrdersStably() throws Exception {
        ChatMessageMapper mapper = mock(ChatMessageMapper.class);
        ChatMessageDaoImpl dao = new ChatMessageDaoImpl();
        setBaseMapper(dao, mapper);

        ChatMessageEntity message = new ChatMessageEntity().setConversationId("42");
        message.setTenantId("spoofed");
        message.setClientId("spoofed");
        dao.insertScoped("tenant-a", "client-a", message);
        assertEquals("tenant-a", message.getTenantId());
        assertEquals("client-a", message.getClientId());
        verify(mapper).insert(message);

        ChatMessageEntity newer = scopedMessage("tenant-a", "client-a", "42", "newer");
        ChatMessageEntity older = scopedMessage("tenant-a", "client-a", "42", "older");
        when(mapper.findExactByConversationScope("tenant-a", "client-a", "42", 500))
                .thenReturn(new ArrayList<>(List.of(newer, older)));

        List<ChatMessageEntity> result = dao.findByConversationIdScoped(
                "tenant-a", "client-a", "42", 500);

        verify(mapper).findExactByConversationScope("tenant-a", "client-a", "42", 500);
        verify(mapper, never()).selectList(any());
        assertEquals(List.of("older", "newer"),
                result.stream().map(ChatMessageEntity::getContent).toList());
    }


    @Test
    void taskThreadMessageSqlIsExactAndDaoRejectsMismatchedRows() throws Exception {
        Method method = ChatMessageMapper.class.getDeclaredMethod(
                "findExactByConversationScope",
                String.class, String.class, String.class, int.class);
        String sql = normalize(String.join(" ", method.getAnnotation(Select.class).value()));
        assertFalse(sql.contains("tenant_id = '0'"), sql);
        assertFalse(sql.contains("or tenant_id = '0'"), sql);
        for (String column : List.of("tenant_id", "client_id", "conversation_id")) {
            assertTrue(sql.contains("cast(" + column + " as binary)"), sql);
            assertTrue(sql.contains("octet_length(" + column + ")"), sql);
        }

        ChatMessageMapper mapper = mock(ChatMessageMapper.class);
        ChatMessageDaoImpl dao = new ChatMessageDaoImpl();
        setBaseMapper(dao, mapper);
        when(mapper.findExactByConversationScope("tenant-a", "client-a", "42", 10))
                .thenReturn(new ArrayList<>(List.of(
                        scopedMessage("0", "client-a", "42", "must-not-leak"))));
        assertThrows(IllegalStateException.class,
                () -> dao.findByConversationIdScoped("tenant-a", "client-a", "42", 10));
    }

    @Test
    void scopedMessageReadRejectsInvalidScopeAndLimitBeforeMapperAccess() throws Exception {
        ChatMessageMapper mapper = mock(ChatMessageMapper.class);
        ChatMessageDaoImpl dao = new ChatMessageDaoImpl();
        setBaseMapper(dao, mapper);

        for (Runnable invalid : List.<Runnable>of(
                () -> dao.findByConversationIdScoped("", "client-a", "42", 10),
                () -> dao.findByConversationIdScoped("tenant-a ", "client-a", "42", 10),
                () -> dao.findByConversationIdScoped("tenant-a", "client" + (char) 0 + "a", "42", 10),
                () -> dao.findByConversationIdScoped("tenant-a", "client-a", " 42", 10),
                () -> dao.findByConversationIdScoped("tenant-a", "client-a", "42", 0),
                () -> dao.findByConversationIdScoped("tenant-a", "client-a", "42", 501))) {
            assertThrows(IllegalArgumentException.class, invalid::run);
        }

        verify(mapper, never()).findExactByConversationScope(any(), any(), any(), anyInt());
        verify(mapper, never()).selectList(any());
    }

    @Test
    void blankScopeFailsBeforeMapperAccess() {
        AgentTaskThreadMapper mapper = mock(AgentTaskThreadMapper.class);
        AgentTaskThreadDao dao = new AgentTaskThreadDaoImpl(mapper);
        assertThrows(IllegalArgumentException.class,
                () -> dao.findByTaskThread("", "client-a", "task-1", "team", "team"));
        verify(mapper, never()).findExactByTaskThread(any(), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void genericConversationListAlwaysExcludesReservedTaskThreadScope() throws Exception {
        ChatConversationMapper mapper = mock(ChatConversationMapper.class);
        ChatConversationDaoImpl dao = new ChatConversationDaoImpl();
        setBaseMapper(dao, mapper);
        ChatConversationEntity example = new ChatConversationEntity().setJiacn("owner");
        example.setTenantId("owner");
        example.setClientId("client-a");
        dao.selectNonTaskThreadByEntity(example);
        ArgumentCaptor<Wrapper<ChatConversationEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        String sql = normalize(captor.getValue().getSqlSegment());
        assertTrue(sql.contains("conversation_scope_type is null"), sql);
        assertTrue(sql.contains("conversation_scope_type <>"), sql);
    }

    private ChatMessageEntity scopedMessage(
            String tenantId, String clientId, String conversationId, String content) {
        ChatMessageEntity message = new ChatMessageEntity()
                .setConversationId(conversationId)
                .setContent(content);
        message.setTenantId(tenantId);
        message.setClientId(clientId);
        return message;
    }

    private static void setBaseMapper(Object dao, Object mapper) throws Exception {
        Field field = BaseDaoImpl.class.getDeclaredField("baseMapper");
        field.setAccessible(true);
        field.set(dao, mapper);
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT).trim();
    }
}
