package cn.jia.chat.memory;

import cn.jia.core.context.EsContextHolder;
import cn.jia.core.util.DateUtil;
import cn.jia.kefu.entity.KefuChatMessageEntity;
import cn.jia.kefu.service.KefuChatMessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 基于数据库的聊天记忆存储库实现
 * 使用 KefuChatMessageService 作为数据服务层
 * 
 * @author lingma
 * @version 2.1
 */
public class DatabaseChatMemoryRepository implements ChatMemoryRepository {
    
    private final KefuChatMessageService kefuChatMessageService;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
        private KefuChatMessageService kefuChatMessageService;

        /**
         * 设置客服聊天消息服务
         * @param kefuChatMessageService 客服聊天消息服务
         * @return Builder 对象（链式调用）
         */
        public Builder kefuChatMessageService(KefuChatMessageService kefuChatMessageService) {
            this.kefuChatMessageService = kefuChatMessageService;
            return this;
        }

        /**
         * 构建 DatabaseChatMemoryRepository 实例
         * @return DatabaseChatMemoryRepository 实例
         */
        public DatabaseChatMemoryRepository build() {
            Assert.notNull(kefuChatMessageService, "kefuChatMessageService must not be null");
            return new DatabaseChatMemoryRepository(kefuChatMessageService);
        }
    }

    /**
     * 构造函数
     * @param kefuChatMessageService 客服聊天消息服务
     */
    public DatabaseChatMemoryRepository(KefuChatMessageService kefuChatMessageService) {
        Assert.notNull(kefuChatMessageService, "kefuChatMessageService must not be null");
        this.kefuChatMessageService = kefuChatMessageService;
    }

    @Override
    public List<String> findConversationIds() {
        return RetryUtils.execute(RetryUtils.SHORT_RETRY_TEMPLATE, () -> {
            try {
                return kefuChatMessageService.findConversationIds();
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
                List<KefuChatMessageEntity> records = kefuChatMessageService.findByConversationId(conversationId);
                
                return records.stream()
                    .map(this::convertToMessage)
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
                
                int order = 0;
                for (Message msg : messages) {
                    KefuChatMessageEntity entity = new KefuChatMessageEntity();
                    entity.setClientId(contextClientId);
                    entity.setConversationId(conversationId);
                    entity.setMessageType(msg.getMessageType().name());
                    entity.setContent(msg.getText());
                    entity.setMessageOrder(order++);
                    entity.setCreateTime(timestamp);
                    entity.setUpdateTime(timestamp);
                    
                    // 序列化元数据为 JSON
                    if (!msg.getMetadata().isEmpty()) {
                        try {
                            entity.setMetadata(objectMapper.writeValueAsString(msg.getMetadata()));
                        } catch (Exception e) {
                            // 如果序列化失败，忽略元数据
                        }
                    }
                    
                    kefuChatMessageService.create(entity);
                }
                
                return null;
            } catch (Exception e) {
                throw new RuntimeException("Failed to save messages for conversation ID: " + conversationId, e);
            }
        });
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        RetryUtils.execute(RetryUtils.SHORT_RETRY_TEMPLATE, () -> {
            Assert.hasText(conversationId, "conversationId cannot be null or empty");
            try {
                kefuChatMessageService.deleteByConversationId(conversationId);
                return null;
            } catch (Exception e) {
                throw new RuntimeException("Failed to delete messages for conversation ID: " + conversationId, e);
            }
        });
    }

    /**
     * 将 KefuChatMessageEntity 转换为 Message 对象
     * @param entity 聊天消息实体
     * @return Message 对象
     */
    private Message convertToMessage(KefuChatMessageEntity entity) {
        try {
            // 解析元数据
            Map<String, Object> metadata = new HashMap<>();
            if (entity.getMetadata() != null && !entity.getMetadata().trim().isEmpty()) {
                metadata = objectMapper.readValue(entity.getMetadata(), Map.class);
            }
            
            // 根据消息类型创建对应的 Message
            return switch (entity.getMessageType()) {
                case "USER" -> UserMessage.builder()
                        .text(entity.getContent())
                        .metadata(metadata)
                        .build();
                case "ASSISTANT" -> AssistantMessage.builder()
                        .content(entity.getContent())
                        .metadata(metadata)
                        .build();
                case "SYSTEM" -> SystemMessage.builder()
                        .content(entity.getContent())
                        .metadata(metadata)
                        .build();
                default -> throw new IllegalArgumentException("Unknown message type: " + entity.getMessageType());
            };
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert entity to message", e);
        }
    }



    /**
     * 获取当前用户的客户端标识符
     * @return 客户端标识符
     */
    private String getClientId() {
        return EsContextHolder.getContext().getClientId();
    }
}
