package cn.jia.chat.dao.impl;

import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.mapper.ChatMessageMapper;
import cn.jia.common.dao.BaseDaoImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.inject.Named;

import java.util.Collections;
import java.util.List;

/**
 * <p>
 * 聊天消息 DAO 实现类
 * </p>
 *
 * @author chc
 * @since 2026-04-19
 */
@Named
public class ChatMessageDaoImpl extends BaseDaoImpl<ChatMessageMapper, ChatMessageEntity> implements ChatMessageDao {

    @Override
    public List<ChatMessageEntity> findByConversationId(String conversationId) {
        LambdaQueryWrapper<ChatMessageEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessageEntity::getConversationId, conversationId)
                .orderByAsc(ChatMessageEntity::getCreateTime);
        return baseMapper.selectList(wrapper);
    }

    @Override
    public List<ChatMessageEntity> findByConversationIdWithLimit(String conversationId, int limit) {
        LambdaQueryWrapper<ChatMessageEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessageEntity::getConversationId, conversationId)
               .orderByDesc(ChatMessageEntity::getCreateTime)
               .last("LIMIT " + limit);
        List<ChatMessageEntity> result = baseMapper.selectList(wrapper);
        // 由于是倒序查询，需要反转回升序
        Collections.reverse(result);
        return result;
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        LambdaQueryWrapper<ChatMessageEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessageEntity::getConversationId, conversationId);
        List<ChatMessageEntity> list = baseMapper.selectList(wrapper);
        if (list != null && !list.isEmpty()) {
            deleteBatchIds(list.stream().map(ChatMessageEntity::getId).toList());
        }
    }
}
