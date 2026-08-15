package cn.jia.chat.mapper;

import cn.jia.chat.entity.AgentTaskThreadEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgentTaskThreadMapper extends BaseMapper<AgentTaskThreadEntity> {
    @Select("""
            SELECT *
            FROM agent_task_thread
            WHERE (tenant_id = #{tenantId} OR tenant_id = '0')
              AND client_id = #{clientId}
              AND task_id = #{taskId}
              AND thread_type = #{threadType}
              AND thread_key = #{threadKey}
              AND (CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY) OR tenant_id = '0')
              AND (OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId}) OR tenant_id = '0')
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(task_id AS BINARY) = CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(task_id) = OCTET_LENGTH(#{taskId})
              AND CAST(thread_type AS BINARY) = CAST(#{threadType} AS BINARY)
              AND OCTET_LENGTH(thread_type) = OCTET_LENGTH(#{threadType})
              AND CAST(thread_key AS BINARY) = CAST(#{threadKey} AS BINARY)
              AND OCTET_LENGTH(thread_key) = OCTET_LENGTH(#{threadKey})
            LIMIT 1
            """)
    AgentTaskThreadEntity findExactByTaskThread(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId,
            @Param("threadType") String threadType,
            @Param("threadKey") String threadKey);

    @Select("""
            SELECT *
            FROM agent_task_thread
            WHERE (tenant_id = #{tenantId} OR tenant_id = '0')
              AND client_id = #{clientId}
              AND task_id = #{taskId}
              AND thread_type = #{threadType}
              AND thread_key = #{threadKey}
              AND (CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY) OR tenant_id = '0')
              AND (OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId}) OR tenant_id = '0')
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(task_id AS BINARY) = CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(task_id) = OCTET_LENGTH(#{taskId})
              AND CAST(thread_type AS BINARY) = CAST(#{threadType} AS BINARY)
              AND OCTET_LENGTH(thread_type) = OCTET_LENGTH(#{threadType})
              AND CAST(thread_key AS BINARY) = CAST(#{threadKey} AS BINARY)
              AND OCTET_LENGTH(thread_key) = OCTET_LENGTH(#{threadKey})
            LIMIT 1
            FOR UPDATE
            """)
    AgentTaskThreadEntity findExactByTaskThreadForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId,
            @Param("threadType") String threadType,
            @Param("threadKey") String threadKey);

    @Select("""
            SELECT *
            FROM agent_task_thread
            WHERE (tenant_id = #{tenantId} OR tenant_id = '0')
              AND client_id = #{clientId}
              AND conversation_id = #{conversationId}
              AND (CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY) OR tenant_id = '0')
              AND (OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId}) OR tenant_id = '0')
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(conversation_id AS BINARY) = CAST(#{conversationId} AS BINARY)
              AND OCTET_LENGTH(conversation_id) = OCTET_LENGTH(#{conversationId})
            LIMIT 1
            """)
    AgentTaskThreadEntity findExactByConversationId(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("conversationId") String conversationId);

    @Select("""
            SELECT *
            FROM agent_task_thread
            WHERE conversation_id = #{conversationId}
              AND CAST(conversation_id AS BINARY) = CAST(#{conversationId} AS BINARY)
              AND OCTET_LENGTH(conversation_id) = OCTET_LENGTH(#{conversationId})
            LIMIT 1
            """)
    AgentTaskThreadEntity findAnyExactByConversationId(
            @Param("conversationId") String conversationId);
}
