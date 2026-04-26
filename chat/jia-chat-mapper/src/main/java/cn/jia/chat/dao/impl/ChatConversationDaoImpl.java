package cn.jia.chat.dao.impl;

import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.mapper.ChatConversationMapper;
import cn.jia.common.dao.BaseDaoImpl;
import jakarta.inject.Named;

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

}
