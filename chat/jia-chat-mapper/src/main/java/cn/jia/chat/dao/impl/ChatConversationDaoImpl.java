package cn.jia.chat.dao.impl;

import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.entity.AgentTaskThreadConstants;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.mapper.ChatConversationMapper;
import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.core.util.StringUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.inject.Named;

import java.util.List;

/**
 * <p>
 * 聊天会话 DAO 实现类
 * </p>
 *
 * @author chc
 * @since 2026-04-19
 */
@Named
public class ChatConversationDaoImpl extends BaseDaoImpl<ChatConversationMapper, ChatConversationEntity> implements ChatConversationDao {

    @Override
    public ChatConversationEntity findScopedById(
            String tenantId, String clientId, String conversationId) {
        requireId(tenantId, "tenantId");
        requireId(clientId, "clientId");
        requireId(conversationId, "conversationId");
        Long id;
        try {
            id = Long.valueOf(conversationId);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("conversationId is invalid");
        }
        return baseMapper.selectOne(new LambdaQueryWrapper<ChatConversationEntity>()
                .eq(ChatConversationEntity::getTenantId, tenantId)
                .eq(ChatConversationEntity::getClientId, clientId)
                .eq(ChatConversationEntity::getId, id)
                .last("limit 1"));
    }

    @Override
    public List<ChatConversationEntity> selectNonTaskThreadByEntity(ChatConversationEntity example) {
        ChatConversationEntity safe = example == null ? new ChatConversationEntity() : example;
        QueryWrapper<ChatConversationEntity> wrapper = new QueryWrapper<>(safe);
        appendQueryWrapper(safe, wrapper);
        wrapper.and(nested -> nested.isNull("conversation_scope_type")
                .or().ne("conversation_scope_type", AgentTaskThreadConstants.CONVERSATION_SCOPE_TYPE));
        return baseMapper.selectList(wrapper);
    }

    private void requireId(String value, String field) {
        if (StringUtil.isBlank(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
