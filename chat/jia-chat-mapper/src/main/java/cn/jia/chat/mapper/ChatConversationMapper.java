package cn.jia.chat.mapper;

import cn.jia.chat.entity.ChatConversationEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Chat conversation mapper. */
public interface ChatConversationMapper extends BaseMapper<ChatConversationEntity> {
    @Select("""
            SELECT *
            FROM chat_conversation
            WHERE id = #{id}
              AND (tenant_id = #{tenantId} OR tenant_id = '0')
              AND client_id = #{clientId}
              AND (CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY) OR tenant_id = '0')
              AND (OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId}) OR tenant_id = '0')
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
            LIMIT 1
            """)
    ChatConversationEntity findExactScopedById(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("id") Long id);
}
