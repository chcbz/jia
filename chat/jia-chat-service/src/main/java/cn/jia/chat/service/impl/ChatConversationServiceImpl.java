package cn.jia.chat.service.impl;

import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.service.ChatConversationService;
import cn.jia.core.context.EsContextHolder;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 聊天会话服务实现类
 *
 * @author chc
 * @since 2026-04-19
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatConversationServiceImpl implements ChatConversationService {

    private final ChatConversationDao chatConversationDao;

    private final ChatMessageDao chatMessageDao;

    @Override
    public PageInfo<ChatConversationEntity> findPage(ChatConversationEntity example, int pageNum, int pageSize, String orderBy) {
        PageHelper.startPage(pageNum, pageSize, orderBy);
        // 如果未指定查询条件，默认按当前用户过滤
        if (example == null) {
            example = new ChatConversationEntity();
        }
        if (example.getJiacn() == null || example.getJiacn().isEmpty()) {
            example.setJiacn(EsContextHolder.getContext().getJiacn());
        }
        return PageInfo.of(chatConversationDao.selectByEntity(example));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteConversation(String conversationId) {
        // 删除会话记录
        chatConversationDao.deleteById(Long.valueOf(conversationId));
        // 删除该会话的所有消息
        chatMessageDao.deleteByConversationId(conversationId);
    }

    @Override
    public List<ChatMessageEntity> findByConversationId(String conversationId) {
        return chatMessageDao.findByConversationId(conversationId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatConversationEntity create(ChatConversationEntity entity) {
        chatConversationDao.insert(entity);
        return entity;
    }

    @Override
    public ChatConversationEntity get(String conversationId) {
        ChatConversationEntity query = new ChatConversationEntity();
        query.setId(Long.valueOf(conversationId));
        List<ChatConversationEntity> list = chatConversationDao.selectByEntity(query);
        return list != null && !list.isEmpty() ? list.getFirst() : null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatConversationEntity update(ChatConversationEntity entity) {
        chatConversationDao.updateById(entity);
        return entity;
    }
}
