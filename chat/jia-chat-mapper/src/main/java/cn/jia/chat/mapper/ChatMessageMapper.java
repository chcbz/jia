package cn.jia.chat.mapper;

import cn.jia.chat.entity.ChatMessageEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** Chat message mapper. */
public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {
    @Select("""
            SELECT *
            FROM chat_message
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND conversation_id = #{conversationId}
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(conversation_id AS BINARY) = CAST(#{conversationId} AS BINARY)
              AND OCTET_LENGTH(conversation_id) = OCTET_LENGTH(#{conversationId})
            ORDER BY create_time DESC, id DESC
            LIMIT #{limit}
            """)
    List<ChatMessageEntity> findExactByConversationScope(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("conversationId") String conversationId,
            @Param("limit") int limit);
}
