# Chat 聊天模块规格文档

## 模块概述

Chat 聊天模块提供AI对话功能，支持对话会话管理和消息存储，集成 Spring AI 实现对话历史管理。

- **子模块**: api, core, mapper, service, starter
- **包名**: cn.jia.chat
- **版本**: 1.1.2-SNAPSHOT

## 技术实现

### 依赖框架

| 组件 | 说明 |
|------|------|
| Spring AI | AI对话框架 |
| MyBatis Plus | 数据持久化 |
| Redis | 缓存/内存存储 |
| Elasticsearch | 可选的对话历史存储 |

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

#### ChatMessageEntity

消息实体，继承 `BaseEntity`：

```java
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("chat_message")
@Schema(name = "ChatMessage对象", description = "聊天消息")
public class ChatMessageEntity extends BaseEntity {
    private Long id;                // 消息ID（自增）
    private String conversationId;  // 对话ID
    private String messageType;     // 消息类型（USER/ASSISTANT/SYSTEM）
    private String content;          // 消息内容
    private String metadata;         // 消息元数据（JSON格式）
    private Integer messageOrder;    // 消息顺序（同一会话内的排序）
    private String jiacn;            // 用户ID（冗余字段，便于长效记忆汇总）
}
```

#### ChatConversationEntity

对话会话实体，继承 `BaseEntity`：

```java
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("chat_conversation")
@Schema(name = "ChatConversation对象", description = "聊天会话")
public class ChatConversationEntity extends BaseEntity {
    private Long id;           // 会话ID（自增主键）
    private String title;      // 会话标题
    private String jiacn;      // 用户ID（多租户标识）
    private Integer status;    // 会话状态（ACTIVE/CLOSED）
}
```

### 数据访问层

#### ChatMessageDao 接口

```java
public interface ChatMessageDao extends IBaseDao<ChatMessageEntity> {
    // 根据会话ID查询消息列表（按messageOrder排序）
    List<ChatMessageEntity> findByConversationId(String conversationId);
    
    // 根据会话ID删除消息
    void deleteByConversationId(String conversationId);
    
    // 查询所有会话ID
    List<String> findConversationIds();
}
```

#### DAO实现

DAO实现类位于 `jia-chat-service` 模块的 `dao` 包，继承 `IBaseDao`。

### 内存存储

实现 Spring AI 的 `ChatMemoryRepository` 接口，支持对话历史的持久化存储。

#### DatabaseChatMemoryRepository

数据库存储实现：

```java
public class DatabaseChatMemoryRepository implements ChatMemoryRepository {
    
    // 查询所有会话ID列表
    @Override
    public List<String> findConversationIds();
    
    // 根据会话ID加载历史消息
    @Override
    public List<Message> findByConversationId(String conversationId);
    
    // 批量保存消息到数据库
    @Override
    public void saveAll(String conversationId, List<Message> messages);
    
    // 根据会话ID删除消息
    @Override
    public void deleteByConversationId(String conversationId);
    
    // 内部方法：转换实体为Message对象
    private Message convertToMessage(ChatMessageEntity entity);
}
```

**特性**：
- 使用 Builder 模式构建实例
- 支持重试机制（RetryUtils）
- 支持多租户（通过 EsContextHolder 获取 clientId）
- 消息元数据序列化存储为 JSON

#### ElasticsearchChatMemoryRepository

Elasticsearch 存储实现，可选的分布式存储方案：

```java
public class ElasticsearchChatMemoryRepository implements ChatMemoryRepository {
    
    // 通过聚合查询获取所有会话ID
    @Override
    public List<String> findConversationIds();
    
    // 根据会话ID从ES加载历史消息
    @Override
    public List<Message> findByConversationId(String conversationId);
    
    // 批量保存消息到ES索引
    @Override
    public void saveAll(String conversationId, List<Message> messages);
    
    // 通过查询删除指定会话的消息
    @Override
    public void deleteByConversationId(String conversationId);
}
```

**特性**：
- 使用 Builder 模式构建实例
- 支持重试机制（RetryUtils）
- 支持多租户（通过 jiacn 字段过滤）
- 文档结构包含 id、content 和 metadata
- 使用聚合查询获取唯一会话ID

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
## 数据库表结构

### chat_conversation（聊天会话表）

| 字段名 | 类型 | 说明 |
|--------|------|------|
| id | bigint | 主键ID（自增） |
| title | varchar(500) | 会话标题 |
| jiacn | varchar(50) | 用户ID（多租户标识） |
| status | int | 会话状态（0-活跃/1-关闭） |
| create_time | bigint | 创建时间戳 |
| update_time | bigint | 更新时间戳 |
| client_id | varchar(50) | 应用标识符 |
| tenant_id | varchar(50) | 租户ID |

### chat_message（聊天消息表）

| 字段名 | 类型 | 说明 |
|--------|------|------|
| id | bigint | 主键ID（自增） |
| conversation_id | bigint | 会话ID（外键） |
| message_type | varchar(20) | 消息类型（USER/ASSISTANT/SYSTEM） |
| content | text | 消息内容 |
| metadata | text | 消息元数据（JSON格式） |
| message_order | int | 消息顺序 |
| create_time | bigint | 创建时间戳 |
| update_time | bigint | 更新时间戳 |
| client_id | varchar(50) | 应用标识符 |
| tenant_id | varchar(50) | 租户ID |
| jiacn | varchar(50) | 用户ID（冗余字段，便于长效记忆汇总） |

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
