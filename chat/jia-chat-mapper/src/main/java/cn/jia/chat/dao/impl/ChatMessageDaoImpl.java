package cn.jia.chat.dao.impl;

import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.mapper.ChatMessageMapper;
import cn.jia.common.dao.BaseDaoImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.inject.Named;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    @Override
    public List<String> findPendingConversationIds(String syncStatus, int limit) {
        LambdaQueryWrapper<ChatMessageEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessageEntity::getSyncStatus, syncStatus)
               .orderByAsc(ChatMessageEntity::getCreateTime)
               .last("LIMIT " + limit);
        List<ChatMessageEntity> list = baseMapper.selectList(wrapper);
        if (list != null && !list.isEmpty()) {
            return list.stream()
                    .map(ChatMessageEntity::getConversationId)
                    .distinct()
                    .toList();
        }
        return Collections.emptyList();
    }

    @Override
    public void updateSyncStatusByConversationId(String conversationId, String syncStatus) {
        LambdaQueryWrapper<ChatMessageEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessageEntity::getConversationId, conversationId);
        List<ChatMessageEntity> list = baseMapper.selectList(wrapper);
        if (list != null && !list.isEmpty()) {
            list.forEach(entity -> entity.setSyncStatus(syncStatus));
            updateBatchById(list);
        }
    }

    @Override
    public List<Long> findExpiredMessageIds(long beforeTime, int limit) {
        LambdaQueryWrapper<ChatMessageEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.lt(ChatMessageEntity::getCreateTime, beforeTime)
               .select(ChatMessageEntity::getId)
               .orderByAsc(ChatMessageEntity::getId)
               .last("LIMIT " + limit);
        List<ChatMessageEntity> list = baseMapper.selectList(wrapper);
        if (list != null && !list.isEmpty()) {
            return list.stream().map(ChatMessageEntity::getId).toList();
        }
        return Collections.emptyList();
    }

    @Override
    public List<String> findActiveJiacns(long sinceTime) {
        LambdaQueryWrapper<ChatMessageEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.gt(ChatMessageEntity::getCreateTime, sinceTime)
               .select(ChatMessageEntity::getJiacn);
        List<ChatMessageEntity> list = baseMapper.selectList(wrapper);
        if (list != null && !list.isEmpty()) {
            // 去重
            Set<String> jiacnSet = new HashSet<>();
            for (ChatMessageEntity entity : list) {
                if (entity.getJiacn() != null) {
                    jiacnSet.add(entity.getJiacn());
                }
            }
            return List.copyOf(jiacnSet);
        }
        return Collections.emptyList();
    }
}