package cn.jia.agent.memory;

import cn.jia.core.context.EsContextHolder;
import cn.jia.core.util.DateUtil;
import cn.jia.kefu.entity.KefuFaqEntity;
import cn.jia.kefu.service.KefuFaqService;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 基于数据库的聊天记忆存储库实现
 * 使用 KefuFaqService 作为数据服务层
 * 
 * @author lingma
 * @version 1.0
 */
public class DatabaseChatMemoryRepository implements ChatMemoryRepository {
    
    private final KefuFaqService kefuFaqService;

    /**
     * 创建 Builder 实例
     * @return Builder 对象
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder 类，用于构建 DatabaseChatMemoryRepository 实例
     */
    public static class Builder {
        private KefuFaqService kefuFaqService;

        /**
         * 设置客服 FAQ 服务
         * @param kefuFaqService 客服 FAQ 服务
         * @return Builder 对象（链式调用）
         */
        public Builder kefuFaqService(KefuFaqService kefuFaqService) {
            this.kefuFaqService = kefuFaqService;
            return this;
        }

        /**
         * 构建 DatabaseChatMemoryRepository 实例
         * @return DatabaseChatMemoryRepository 实例
         */
        public DatabaseChatMemoryRepository build() {
            Assert.notNull(kefuFaqService, "kefuFaqService must not be null");
            return new DatabaseChatMemoryRepository(kefuFaqService);
        }
    }

    /**
     * 构造函数
     * @param kefuFaqService 客服 FAQ 服务
     */
    public DatabaseChatMemoryRepository(KefuFaqService kefuFaqService) {
        Assert.notNull(kefuFaqService, "kefuFaqService must not be null");
        this.kefuFaqService = kefuFaqService;
    }

    @Override
    public List<String> findConversationIds() {
        return RetryUtils.execute(RetryUtils.SHORT_RETRY_TEMPLATE, () -> {
            try {
                // 从用户上下文获取 clientId
                String contextClientId = getClientId();
                
                // 构建查询条件
                KefuFaqEntity query = new KefuFaqEntity();
                query.setClientId(contextClientId);
                query.setType("chat_memory");
                
                // 查询所有相关记录
                List<KefuFaqEntity> allRecords = kefuFaqService.findList(query);
                
                // 提取唯一的 conversationId
                return allRecords.stream()
                    .map(KefuFaqEntity::getConversationId)
                    .distinct()
                    .collect(Collectors.toList());
            } catch (Exception e) {
                throw new RuntimeException("Failed to find conversation IDs from database", e);
            }
        });
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return RetryUtils.execute(RetryUtils.SHORT_RETRY_TEMPLATE, () -> {
            Assert.hasText(conversationId, "conversationId cannot be null or empty");
            try {
                String contextClientId = getClientId();
                
                // 构建查询条件
                KefuFaqEntity query = new KefuFaqEntity();
                query.setClientId(contextClientId);
                query.setType("chat_memory");
                query.setConversationId(conversationId);
                
                // 按创建时间排序查询
                List<KefuFaqEntity> records = kefuFaqService.findList(query);
                
                // 将每条记录拆分为两个 Message：user 和 assistant
                return records.stream()
                    .flatMap(entity -> convertToMessages(entity).stream())
                    .collect(Collectors.toList());
            } catch (Exception e) {
                throw new RuntimeException("Failed to find messages by conversation ID: " + conversationId, e);
            }
        });
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        RetryUtils.execute(RetryUtils.SHORT_RETRY_TEMPLATE, () -> {
            Assert.hasText(conversationId, "conversationId cannot be null or empty");
            Assert.notNull(messages, "messages cannot be null");
            Assert.noNullElements(messages, "messages cannot contain null elements");

            try {
                String contextClientId = getClientId();
                long timestamp = DateUtil.nowTime();
                
                for (Message msg : messages) {
                    // 创建新的 FAQ 实体
                    KefuFaqEntity entity = new KefuFaqEntity();
                    entity.setId(generateMessageId());
                    entity.setType("chat_memory");
                    entity.setClientId(contextClientId);
                    entity.setConversationId(conversationId);
                    entity.setTitle(msg.getText());
                    entity.setContent("");
                    entity.setClick(0);
                    entity.setUseful(0);
                    entity.setUseless(0);
                    entity.setStatus(1);
                    entity.setCreateTime(timestamp);
                    entity.setUpdateTime(timestamp);
                    
                    // 保存消息
                    kefuFaqService.create(entity);
                }
                
                return null;
            } catch (Exception e) {
                throw new RuntimeException("Failed to save messages for conversation ID: " + conversationId, e);
            }
        });
    }

    /**
     * 通过会话 ID 删除消息
     * @param conversationId 会话 ID
     */
    @Override
    public void deleteByConversationId(String conversationId) {
        RetryUtils.execute(RetryUtils.SHORT_RETRY_TEMPLATE, () -> {
            Assert.hasText(conversationId, "conversationId cannot be null or empty");
            try {
                String contextClientId = getClientId();
                
                // 查询该会话的所有消息
                KefuFaqEntity query = new KefuFaqEntity();
                query.setClientId(contextClientId);
                query.setType("chat_memory");
                query.setConversationId(conversationId);
                
                List<KefuFaqEntity> records = kefuFaqService.findList(query);
                
                // 批量删除
                for (KefuFaqEntity record : records) {
                    kefuFaqService.delete(record.getId());
                }
                
                return null;
            } catch (Exception e) {
                throw new RuntimeException("Failed to delete messages for conversation ID: " + conversationId, e);
            }
        });
    }

    /**
     * 将 KefuFaqEntity 转换为两个 Message 对象（用户问题和系统回复）
     * @param entity KefuFaqEntity 实体
     * @return Message 对象列表（包含 user 和 assistant 两个消息）
     */
    private List<Message> convertToMessages(KefuFaqEntity entity) {
        List<Message> messages = new ArrayList<>();
        
        // 构建元数据
        Map<String, Object> metadata = Map.of(
            "jiacn", entity.getClientId(),
            "conversationId", entity.getConversationId(),
            "timestamp", entity.getCreateTime()
        );
        
        // 创建用户消息（使用 title 字段）
        if (entity.getTitle() != null && !entity.getTitle().trim().isEmpty()) {
            Map<String, Object> userMetadata = new HashMap<>(metadata);
            userMetadata.put("messageType", "USER");
            
            UserMessage userMessage = UserMessage.builder()
                    .text(entity.getTitle())
                    .metadata(userMetadata)
                    .build();
            messages.add(userMessage);
        }
        
        // 创建助手消息（使用 content 字段）
        if (entity.getContent() != null && !entity.getContent().trim().isEmpty()) {
            AssistantMessage assistantMessage = AssistantMessage.builder()
                    .content(entity.getContent())
                    .build();
            messages.add(assistantMessage);
        }
        
        return messages;
    }

    /**
     * 生成消息唯一标识
     * @return 消息 ID
     */
    private Long generateMessageId() {
        // 使用时间戳加随机数生成唯一 ID
        return System.currentTimeMillis() + (long) (Math.random() * 1000);
    }

    /**
     * 获取当前用户的客户端标识符
     * @return 客户端标识符
     */
    private String getClientId() {
        return EsContextHolder.getContext().getClientId();
    }
}
