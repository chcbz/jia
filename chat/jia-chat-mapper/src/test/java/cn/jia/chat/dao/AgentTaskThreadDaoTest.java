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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
    @SuppressWarnings({"rawtypes", "unchecked"})
    void bindingReadsAlwaysStartWithFullScopeAndStableBusinessKey() {
        AgentTaskThreadMapper mapper = mock(AgentTaskThreadMapper.class);
        AgentTaskThreadDao dao = new AgentTaskThreadDaoImpl(mapper);

        dao.findByTaskThread("tenant-a", "client-a", "task-1", "team", "team");
        dao.findByConversationId("tenant-a", "client-a", "42");

        ArgumentCaptor<Wrapper<AgentTaskThreadEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper, org.mockito.Mockito.times(2)).selectOne(captor.capture());
        String byTask = normalize(captor.getAllValues().get(0).getSqlSegment());
        assertTrue(byTask.contains("tenant_id = #{"), byTask);
        assertTrue(byTask.contains("client_id = #{"), byTask);
        assertTrue(byTask.contains("task_id = #{"), byTask);
        assertTrue(byTask.contains("thread_type = #{"), byTask);
        assertTrue(byTask.contains("thread_key = #{"), byTask);
        String byConversation = normalize(captor.getAllValues().get(1).getSqlSegment());
        assertTrue(byConversation.contains("conversation_id = #{"), byConversation);
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

        dao.findByConversationIdScoped("tenant-a", "client-a", "42", 999);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Wrapper<ChatMessageEntity>> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(query.capture());
        String sql = normalize(query.getValue().getSqlSegment());
        assertTrue(sql.contains("tenant_id = #{"), sql);
        assertTrue(sql.contains("client_id = #{"), sql);
        assertTrue(sql.contains("conversation_id = #{"), sql);
        assertTrue(sql.contains("order by create_time desc,id desc"), sql);
        assertTrue(sql.endsWith("limit 500"), sql);
    }

    @Test
    void blankScopeFailsBeforeMapperAccess() {
        AgentTaskThreadMapper mapper = mock(AgentTaskThreadMapper.class);
        AgentTaskThreadDao dao = new AgentTaskThreadDaoImpl(mapper);
        assertThrows(IllegalArgumentException.class,
                () -> dao.findByTaskThread("", "client-a", "task-1", "team", "team"));
        verify(mapper, never()).selectOne(any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void genericConversationListAlwaysExcludesReservedTaskThreadScope() throws Exception {
        ChatConversationMapper mapper = mock(ChatConversationMapper.class);
        ChatConversationDaoImpl dao = new ChatConversationDaoImpl();
        setBaseMapper(dao, mapper);
        dao.selectNonTaskThreadByEntity(new ChatConversationEntity().setJiacn("owner"));
        ArgumentCaptor<Wrapper<ChatConversationEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        String sql = normalize(captor.getValue().getSqlSegment());
        assertTrue(sql.contains("conversation_scope_type is null"), sql);
        assertTrue(sql.contains("conversation_scope_type <>"), sql);
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
