# Chat 聊天模块规格文档

## 模块概述

Chat 聊天模块提供AI对话功能，支持对话会话管理和消息存储。

- **子模块**: api, core, mapper, service, starter
- **包名**: cn.jia.chat
- **版本**: 1.1.2-SNAPSHOT

## 技术实现

### 依赖框架

| 组件 | 说明 |
|------|------|
| MyBatis Plus | 数据持久化 |
| Redis | 缓存/内存存储 |
| Elasticsearch | 可选的对话历史存储 |
| AI Provider | AI服务集成 |

### 核心组件

#### ChatConversationService

对话会话服务接口，主要方法：

```java
public interface ChatConversationService {
    // 创建新对话会话
    ChatConversationEntity createConversation(ChatConversationEntity conversation);
    
    // 获取对话会话
    ChatConversationEntity getConversation(String conversationId);
    
    // 更新对话会话
    void updateConversation(ChatConversationEntity conversation);
    
    // 删除对话会话
    void deleteConversation(String conversationId);
    
    // 获取用户对话列表
    List<ChatConversationEntity> listByUserId(String userId);
}
```

#### ChatMessageService

消息服务接口，主要方法：

```java
public interface ChatMessageService {
    // 发送消息
    ChatMessageEntity sendMessage(ChatMessageDTO messageDTO);
    
    // 获取消息历史
    List<ChatMessageEntity> getMessageHistory(String conversationId);
    
    // 获取对话历史（用于AI上下文）
    List<ChatMessage> getConversationHistory(String conversationId);
    
    // 删除消息
    void deleteMessage(String messageId);
}
```

### 实体类

#### ChatConversationEntity

对话会话实体，继承 `BaseEntity`：

```java
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(name = "ChatConversationEntity")
public class ChatConversationEntity extends BaseEntity {
    private String id;           // 对话ID
    private String userId;       // 用户ID
    private String title;        // 对话标题
    private String model;        // AI模型
    private Integer messages;    // 消息数量
    private String lastMessage;  // 最后一条消息
}
```

#### ChatMessageEntity

消息实体，继承 `BaseEntity`：

```java
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(name = "ChatMessageEntity")
public class ChatMessageEntity extends BaseEntity {
    private String id;              // 消息ID
    private String conversationId;   // 对话ID
    private String role;            // 角色 (user/assistant)
    private String content;         // 消息内容
    private String model;           // AI模型
    private String finishReason;    // 完成原因
    private Long tokens;            // Token数量
}
```

### 数据访问层

#### DAO接口

```java
// 对话DAO
public interface ChatConversationDao {
    ChatConversationEntity selectById(String id);
    List<ChatConversationEntity> selectByUserId(String userId);
    int insert(ChatConversationEntity entity);
    int update(ChatConversationEntity entity);
    int deleteById(String id);
}

// 消息DAO
public interface ChatMessageDao {
    ChatMessageEntity selectById(String id);
    List<ChatMessageEntity> selectByConversationId(String conversationId);
    int insert(ChatMessageEntity entity);
    int deleteById(String id);
    int deleteByConversationId(String conversationId);
}
```

#### DAO实现

DAO实现类位于 `jia-chat-mapper` 模块的 `impl` 子包，使用 MyBatis Plus 的 `BaseMapper` 或自定义 XML 实现。

### 内存存储

#### DatabaseChatMemoryRepository

数据库存储实现，实现 `ChatMemoryRepository` 接口：

```java
public class DatabaseChatMemoryRepository implements ChatMemoryRepository {
    // 从数据库加载历史消息
    @Override
    public List<ChatMessage> getHistory(String conversationId);
    
    // 保存消息到数据库
    @Override
    public void saveMessage(String conversationId, ChatMessage message);
}
```

#### ElasticsearchChatMemoryRepository

Elasticsearch存储实现，可选的分布式存储方案：

```java
public class ElasticsearchChatMemoryRepository implements ChatMemoryRepository {
    // 从ES加载历史消息
    @Override
    public List<ChatMessage> getHistory(String conversationId);
    
    // 保存消息到ES
    @Override
    public void saveMessage(String conversationId, ChatMessage message);
}
```

### DTO对象

#### ChatMessageDTO

消息传输对象：

```java
@Data
@Accessors(chain = true)
@Schema(name = "ChatMessageDTO")
public class ChatMessageDTO implements Serializable {
    private String conversationId;   // 对话ID
    private String content;        // 消息内容
    private String model;          // AI模型
    private Map<String, Object> extra;  // 扩展参数
}
```

## 模块依赖

```
chat/
├── jia-chat-api         # 接口定义
├── jia-chat-core        # 实体、DTO、枚举
├── jia-chat-mapper      # 数据访问实现
├── jia-chat-service     # 业务逻辑
└── jia-chat-starter    # 自动配置
```

## 相关模块

- **jia-common-core**: 提供 BaseEntity
- **jia-common-mapper**: 提供 BaseDaoImpl
- **jia-common-api**: 通用接口

## 使用示例

```java
@Service
public class MyChatService {
    @Autowired
    private ChatConversationService chatConversationService;
    @Autowired
    private ChatMessageService chatMessageService;
    
    public void handleUserMessage(String userId, String content) {
        // 创建新对话
        ChatConversationEntity conversation = new ChatConversationEntity();
        conversation.setUserId(userId);
        conversation.setTitle("新对话");
        chatConversationService.createConversation(conversation);
        
        // 发送消息
        ChatMessageDTO messageDTO = new ChatMessageDTO();
        messageDTO.setConversationId(conversation.getId());
        messageDTO.setContent(content);
        chatMessageService.sendMessage(messageDTO);
    }
}
```
