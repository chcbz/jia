# Chat 长效记忆模块规格文档

## 模块概述

Chat 长效记忆模块实现对话历史的智能记忆管理，通过 LLM 驱动的摘要生成机制，将对话内容提炼为结构化记忆，支持基于向量相似度的语义检索。

- **子模块**: core, service (扩展现有 chat 模块)
- **包名**: cn.jia.chat
- **版本**: 1.2.0-SNAPSHOT

---

## 系统架构

### 整体架构图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              客户端入口                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────┐  ┌─────────────────┐ │
│  │   HTTP API  │  │  WebSocket  │  │  STDIO 命令行   │  │   Agent Tools   │ │
│  │/chat/stream │  │    ws://    │  │  jia.chat.service│  │  TaskTool等     │ │
│  └──────┬──────┘  └──────┬──────┘  └───────┬────────┘  └────────┬────────┘ │
│         │                │                 │                     │          │
└─────────┼────────────────┼─────────────────┼─────────────────────┼──────────┘
          │                │                 │                     │
          ▼                ▼                 ▼                     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          ChatClient (Spring AI)                               │
│  ┌───────────────────────────────────────────────────────────────────────┐   │
│  │                    Advisor Chain (执行顺序)                             │   │
│  │  ┌───────────────────────────────────────────────────────────────┐      │   │
│  │  │ 1. LongTermMemoryAdvisor (Order: DEFAULT + 100)               │      │   │
│  │  │    - 检索跨会话长效记忆                                       │      │   │
│  │  │    - 向量相似度 + 会话权重                                    │      │   │
│  │  ├───────────────────────────────────────────────────────────────┤      │   │
│  │  │ 2. DatabaseChatMemoryAdvisor (Order: DEFAULT)                │      │   │
│  │  │    - 会话级上下文记忆                                        │      │   │
│  │  │    - 最近 N 条消息                                            │      │   │
│  │  ├───────────────────────────────────────────────────────────────┤      │   │
│  │  │ 3. SimpleLoggerAdvisor                                       │      │   │
│  │  │    - 日志记录                                                 │      │   │
│  │  ├───────────────────────────────────────────────────────────────┤      │   │
│  │  │ 4. RequestResponseAdvisor                                    │      │   │
│  │  │    - 请求响应处理                                             │      │   │
│  │  └───────────────────────────────────────────────────────────────┘      │   │
│  └───────────────────────────────────────────────────────────────────────┘   │
│  ┌───────────────────────────────────────────────────────────────────────┐   │
│  │                    Tool Callbacks                                     │   │
│  │  - SyncMcpToolCallbackProvider (MCP 工具)                             │   │
│  │  - TaskTool (子代理任务编排)                                            │   │
│  │  - SkillsTool (技能管理)                                               │   │
│  │  - GrepTool (代码搜索)                                                │   │
│  │  - ShellTools (Shell 命令)                                            │   │
│  │  - SmartWebFetchTool (网页抓取)                                        │   │
│  │  - TodoWriteTool (待办事项)                                            │   │
│  └───────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
          ┌───────────────────────────┼───────────────────────────┐
          ▼                           ▼                           ▼
┌───────────────────────┐   ┌───────────────────────┐   ┌───────────────────────┐
│     MySQL 存储         │   │   Elasticsearch      │   │      Redis           │
│  chat_message         │   │   chat_memory        │   │  - 取消信号订阅       │
│  chat_conversation    │   │   (向量存储)          │   │  - 会话状态          │
│                       │   │                      │   │                      │
│  • 原始消息            │   │  • 记忆摘要          │   │                      │
│  • sync_status        │   │  • KNN 检索          │   │                      │
└───────────────────────┘   └───────────────────────┘   └───────────────────────┘
```

### 双层记忆架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           用户对话请求                                    │
└─────────────────────────────────────────────────────────────────────────┘
                                      │
                    ┌─────────────────┼─────────────────┐
                    ▼                 ▼                 ▼
        ┌───────────────────┐ ┌───────────────────┐ ┌───────────────────┐
        │    短期记忆        │ │    长期记忆        │ │   Agent 工具      │
        │ DatabaseChatMemory │ │ LongTermMemory    │ │ TaskTool 等      │
        │                    │ │ Advisor           │ │                   │
        │ • 会话级上下文      │ │ • 用户级记忆检索    │ │ • 任务分解执行    │
        │ • 最近 N 条消息    │ │ • 向量相似度匹配    │ │ • 技能调用       │
        │ • sync_status     │ │ • 会话权重加权     │ │ • 代码操作       │
        └───────────────────┘ └───────────────────┘ └───────────────────┘
                    │                 │                 │
                    └─────────────────┼─────────────────┘
                                      ▼
                          ┌───────────────────┐
                          │     AI 模型响应     │
                          └───────────────────┘
```

### 数据流向

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  用户消息    │ → │   AI 响应    │ → │  存储原始    │ → │  生成摘要   │
│ chat_message│    │ chat_message│    │   MySQL     │    │   sync      │
└─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘
                                                                │
                                                                ▼
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  定时汇总   │ ← │  日/周/月    │ ← │  向量嵌入    │ ← │  写入向量库  │
│  generate   │    │  汇总摘要    │    │  embedding  │    │  Elasticsearch │
└─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘
```

---

## 客户端入口

### 1. HTTP API (ChatController)

**位置**: `cn.jia.chat.api.ChatController`

#### 主要端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/chat/stream` | POST | 流式聊天，返回 SSE |
| `/chat/stop_stream` | POST | 停止指定会话的流传输 |
| `/chat/conversation/list` | POST | 分页查询会话列表 |
| `/chat/conversation/content` | GET | 获取会话消息内容 |
| `/chat/conversation/update` | POST | 修改会话标题 |
| `/chat/conversation/delete` | DELETE | 删除指定会话 |

#### 流式聊天流程

```
客户端 POST /chat/stream
    ↓
获取或创建会话 (ChatConversationEntity)
    ↓
构建 AI 流 (createAIStream)
    ├── 使用 QuestionAnswerAdvisor 过滤历史消息
    ├── 应用 Advisor Chain (记忆检索)
    └── 流式返回 AI 响应
    ↓
监听 Redis 取消信号 (stop_stream)
    ↓
会话标题自动生成 (首次对话)
    ↓
返回 SSE 格式响应
```

#### Redis 取消信号机制

```text
// 发送取消信号
redisService.publishSignal(conversationId).subscribe();

// 监听取消信号
Flux<String> cancelSignal = redisService.subscribeToChannel(conversationId);
Flux<String> aiStream = createAIStream(...)
        .takeUntilOther(cancelSignal);  // 客户端断开时停止 AI 流
```

### 2. WebSocket (ChatWebSocketHandler)

**位置**: `cn.jia.chat.handler.ChatWebSocketHandler`

```text
// WebSocket 消息格式
{
    "conversationId": "会话ID",
    "content": "用户问题"
}

// 响应: 流式文本消息
// 结束: "[EOM]" 标记
```

### 3. 命令行模式 (StdioChatClientConfig)

**位置**: `cn.jia.chat.config.StdioChatClientConfig`

通过配置 `jia.chat.service.stdio.enable=true` 启用：

```properties
jia.chat.service.stdio.enable=true
```

提供交互式命令行界面，输入 `exit` 退出。

---

## Advisor 组件

### 1. LongTermMemoryAdvisor

**位置**: `cn.jia.chat.advisor.LongTermMemoryAdvisor`

**功能**: 长效记忆检索，支持会话ID优先级

```java
LongTermMemoryAdvisor longTermMemoryAdvisor = LongTermMemoryAdvisor.builder(memoryRepository)
    .memoryTopK(2)                    // 默认: 2
    .similarityThreshold(0.75)         // 默认: 0.75
    .build();
```

**检索策略**:

```
用户检索请求
    ↓
1. 从上下文获取当前会话ID (conversationId) 和 jiacn
    ↓
2. 生成查询向量
    ↓
3. 调用 MemoryRepository.searchWithConversationBoost()
    ├── KNN 检索: 向量相似度
    ├── 权重计算: 当前会话=1.5, 同一天=1.2, 其他=1.0
    ↓
4. 过滤低于 similarityThreshold 的结果
    ↓
5. 返回 topK 结果，格式化为上下文文本
```

### 2. DatabaseChatMemoryAdvisor

**位置**: `cn.jia.chat.advisor.DatabaseChatMemoryAdvisor`

**功能**: 会话级上下文记忆

```java
DatabaseChatMemoryAdvisor contextMemoryAdvisor = DatabaseChatMemoryAdvisor.builder(chatMessageDao)
    .maxMessages(10)                   // 默认: 10
    .build();
```

**工作原理**:
- `before`: 查询历史消息用于上下文增强，并保存当前用户消息
- `after`: 保存 AI 响应消息到数据库，设置 `sync_status = PENDING`

### 3. RequestResponseAdvisor

**位置**: `cn.jia.chat.advisor.RequestResponseAdvisor`

**功能**: 请求响应处理增强

### 执行顺序

| 顺序 | Advisor | Order | 功能 |
|------|---------|-------|------|
| 1 | LongTermMemoryAdvisor | DEFAULT + 100 | 检索跨会话长效记忆 |
| 2 | DatabaseChatMemoryAdvisor | DEFAULT | 检索当前会话短期消息 |
| 3 | SimpleLoggerAdvisor | - | 日志记录 |
| 4 | RequestResponseAdvisor | - | 请求响应处理 |

---

## Agent Tools

### ChatClientConfig 配置

**位置**: `cn.jia.chat.config.ChatClientConfig`

```java
@Bean
public ChatClient chatClient(ChatClient.Builder chatClientBuilder,
        List<McpSyncClient> mcpSyncClients, MemoryRepository memoryRepository,
        ChatMessageDao chatMessageDao) {
    
    // 长效记忆检索
    LongTermMemoryAdvisor longTermMemoryAdvisor = LongTermMemoryAdvisor.builder(memoryRepository)
            .memoryTopK(2)
            .similarityThreshold(0.75)
            .build();
    
    // 短期上下文记忆
    DatabaseChatMemoryAdvisor contextMemoryAdvisor = DatabaseChatMemoryAdvisor.builder(chatMessageDao)
            .maxMessages(10)
            .build();
    
    // 请求响应处理
    RequestResponseAdvisor chatControllerAdvisor = new RequestResponseAdvisor();
    
    // 子代理任务编排
    var taskTool = TaskTool.builder()
            .subagentTypes(ClaudeSubagentType.builder()
                    .chatClientBuilder("default", chatClientBuilder.clone())
                    .skillsDirectories(List.of(skillsRootDirectories))
                    .build())
            .build();
    
    return chatClientBuilder
            // MCP 工具
            .defaultToolCallbacks(SyncMcpToolCallbackProvider.builder()
                    .mcpClients(mcpSyncClients).build())
            // 子代理
            .defaultToolCallbacks(taskTool)
            .defaultToolCallbacks(SkillsTool.builder()
                    .addSkillsDirectories(List.of(skillsRootDirectories)).build())
            // 核心工具
            .defaultTools(
                    GrepTool.builder().build(),
                    ShellTools.builder().build(),
                    SmartWebFetchTool.builder(chatClientBuilder.clone().build()).build()
            )
            // 待办事项
            .defaultTools(TodoWriteTool.builder().build())
            // Advisors
            .defaultAdvisors(longTermMemoryAdvisor)
            .defaultAdvisors(contextMemoryAdvisor)
            .defaultAdvisors(SimpleLoggerAdvisor.builder().build())
            .defaultAdvisors(chatControllerAdvisor)
            .build();
}
```

### 工具列表

| 工具 | 说明 | 类型 |
|------|------|------|
| SyncMcpToolCallbackProvider | MCP 协议工具调用 | MCP |
| TaskTool | 任务分解与子代理编排 | Spring AI |
| SkillsTool | 技能加载与执行 | Spring AI |
| GrepTool | 代码内容搜索 | Spring AI |
| ShellTools | Shell 命令执行 | Spring AI |
| SmartWebFetchTool | 智能网页抓取 | Spring AI |
| TodoWriteTool | 待办事项管理 | Spring AI |

---

## 数据存储

### 原始消息 (MySQL)

**表**: chat_message

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint | 主键 |
| conversation_id | varchar(100) | 会话ID；支持数值型会话 ID 和 OpenClaw 字符串会话 ID |
| message_type | varchar | USER/ASSISTANT/SYSTEM |
| content | text | 消息内容 |
| jiacn | varchar | 用户标识 |
| sync_status | varchar | PENDING/SYNCED |
| create_time | bigint | 创建时间 |

**保留策略**: 90 天

### 摘要记忆 (Elasticsearch)

**索引**: chat_memory

**Mapping**:

```json
{
  "mappings": {
    "properties": {
      "id": { "type": "keyword" },
      "jiacn": { "type": "keyword" },
      "conversation_id": { "type": "keyword" },
      "content": {
        "type": "text",
        "analyzer": "ik_max_word"
      },
      "summary_type": { "type": "keyword" },
      "topic": { "type": "keyword" },
      "key_points": { "type": "keyword" },
      "categories": { "type": "keyword" },
      "preferences": { "type": "keyword" },
      "period_start": { "type": "long" },
      "period_end": { "type": "long" },
      "conversation_count": { "type": "integer" },
      "timestamp": { "type": "long" },
      "embedding": {
        "type": "dense_vector",
        "dims": 1024,
        "index": true,
        "similarity": "cosine"
      }
    }
  },
  "settings": {
    "index": {
      "number_of_shards": "1",
      "number_of_replicas": "0"
    }
  }
}
```

**保留策略**:

| 类型 | 保留期 | 说明 |
|------|--------|------|
| conversation | 90 天 | 会话级摘要 |
| daily_summary | 永久 | 日汇总（最近 30 条有效） |
| weekly_summary | 永久 | 周汇总（最近 12 条有效） |
| monthly_summary | 永久 | 月汇总（长期记忆） |

---

## 核心服务组件

### 1. LongTermMemoryService

**位置**: `cn.jia.chat.service.LongTermMemoryService`

```java
public interface LongTermMemoryService {
    /**
     * 同步会话消息到向量库
     */
    void syncConversation(String conversationId);
    
    /**
     * 同步待处理会话
     */
    void syncPendingConversations();
    
    /**
     * 生成日汇总
     */
    void generateDailySummary();
    
    /**
     * 生成周汇总
     */
    void generateWeeklySummary();
    
    /**
     * 生成月汇总
     */
    void generateMonthlySummary();
    
    /**
     * 清理过期记忆
     */
    void cleanupExpiredMemories();
}
```

### 2. SummaryGenerator

**位置**: `cn.jia.chat.service.SummaryGenerator`

```java
public interface SummaryGenerator {
    /**
     * 生成会话摘要
     */
    String summarizeConversation(List<ChatMessageEntity> messages);
    
    /**
     * 生成日汇总
     */
    String generateDailySummary(List<String> conversationSummaries, String jiacn, String date);
    
    /**
     * 生成周汇总
     */
    String generateWeeklySummary(List<String> dailySummaries, String jiacn, String weekStartDate);
    
    /**
     * 生成月汇总
     */
    String generateMonthlySummary(List<String> weeklySummaries, String jiacn, String month);
}
```

### 3. MemoryRepository

**位置**: `cn.jia.chat.memory.MemoryRepository`

```java
public interface MemoryRepository {
    void save(MemoryDocument document);
    void saveAll(List<MemoryDocument> documents);
    
    /**
     * 搜索记忆 (带会话权重)
     */
    List<MemoryDocument> searchWithConversationBoost(String jiacn, String userMessage, 
            String currentConversationId, int topK, double similarityThreshold);
    
    List<MemoryDocument> search(String jiacn, float[] queryVector, int topK);
    List<String> findConversationSummariesByJiacn(String jiacn, long sinceTime);
    List<String> findDailySummariesByJiacn(String jiacn, long sinceTime);
    List<String> findWeeklySummariesByJiacn(String jiacn, long sinceTime);
    void deleteExpiredDocuments(long beforeTime);
    void deleteOldDocuments(String summaryType, int keepCount);
    long countByJiacn(String jiacn);
    
    /**
     * 生成文本嵌入向量
     */
    float[] generateEmbedding(String text);
}
```

### 4. MemoryJobHandler

**位置**: `cn.jia.chat.job.MemoryJobHandler`

实现 `JobHandler` 接口，通过 task 项目的调度能力执行：

```java
@Slf4j
@Component
public class MemoryJobHandler implements JobHandler {
    private static final String GROUP = "chat";
    
    @Autowired
    private LongTermMemoryService longTermMemoryService;

    @Override
    public String getName() { return "memory_sync"; }
    @Override
    public String getDescription() { return "对话记忆同步和汇总"; }
    @Override
    public String getGroup() { return GROUP; }
    @Override
    public String getCron() { return "0 0 * * * ?"; }  // 每小时执行
    
    @Override
    public String execute(JobContext context) throws Exception {
        log.info("Starting memory sync job");
        longTermMemoryService.syncPendingConversations();
        log.info("Memory sync job completed");
        return "success";
    }
}
```

---

## 配置项

```properties
# 批处理
chat.memory.batch-size=100

# 清理策略
chat.memory.retention-days=90

# 向量维度
chat.memory.embedding.dimension=1024

# 检索配置
chat.memory.retrieval.current-conversation-weight=1.5
chat.memory.retrieval.same-day-weight=1.2

# 记忆检索 (ChatClientConfig)
chat.memory.retrieval.top-k=2
chat.memory.retrieval.similarity-threshold=0.75

# 上下文记忆
chat.memory.context.max-messages=10

# 定时任务
chat.memory.job.sync-cron=0 0 * * * ?
chat.memory.job.daily-summary-hour=2
chat.memory.job.weekly-summary-hour=3
chat.memory.job.monthly-summary-hour=4

# 汇总窗口配置
chat.memory.summary.daily-window-days=7
chat.memory.summary.daily-summary-keep=30
chat.memory.summary.weekly-lookback-days=30
chat.memory.summary.monthly-lookback-days=90

# 命令行模式
jia.chat.service.stdio.enable=false

# Agent Skills 目录
chat.agent.skills.dirs=
```

---

## 工作流程

### 1. 对话流程

```
用户提问 
    ↓
[LongTermMemoryAdvisor] 检索相似记忆并注入上下文
    ↓
[DatabaseChatMemoryAdvisor] 查询最近10条消息作为上下文
    ↓
[DatabaseChatMemoryAdvisor] 保存用户消息到数据库 (sync_status=PENDING)
    ↓
AI 模型响应
    ↓
[DatabaseChatMemoryAdvisor] 保存 AI 响应到数据库 (sync_status=PENDING)
    ↓
返回响应给用户
```

### 2. 同步流程

```
[定时任务] 每小时触发
    ↓
查询 sync_status = 'PENDING' 的会话
    ↓
批量处理会话列表 (每批 100 个)
    ↓
对每个会话:
    ├── 获取完整消息列表
    ├── 调用 LLM 生成摘要
    ├── 写入向量库 (summary_type = 'conversation')
    └── 更新 sync_status = 'SYNCED'
```

### 3. 汇总流程

```
[日汇总] 每天凌晨2点触发
    ↓
查询所有活跃用户 (近7天有对话)
    ↓
对每个用户:
    ├── 获取近7天的会话摘要列表
    ├── 调用 LLM 生成日汇总
    ├── 写入向量库
    └── 清理旧的日汇总 (保留最近30条)

[周汇总] 每周日凌晨3点触发
[月汇总] 每月1日凌晨4点触发
```

### 4. 清理流程

```
[定时任务] 每周日凌晨触发
    ↓
删除向量库中过期的 conversation 记录 (创建时间 > 90天)
    ↓
删除 MySQL 中过期的 chat_message 记录 (create_time > 90天)
    ↓
清理旧的 daily_summary (保留最近30条)
    ↓
清理旧的 weekly_summary (保留最近12条)
```

---

## 已新增文件

| 文件 | 位置 | 说明 |
|------|------|------|
| LongTermMemoryService.java | jia-chat-api | 服务接口 |
| LongTermMemoryServiceImpl.java | jia-chat-service | 服务实现 |
| SummaryGenerator.java | jia-chat-api | 摘要生成接口 |
| DefaultSummaryGenerator.java | jia-chat-service | LLM 摘要生成实现 |
| MemoryJobHandler.java | jia-chat-service | 定时任务处理器 |
| MemoryRepository.java | jia-chat-service | 向量存储库接口 |
| MemoryRepositoryImpl.java | jia-chat-service | ES 操作封装 |
| MemoryDocument.java | jia-chat-service | 记忆文档模型 |
| LongTermMemoryAdvisor.java | jia-chat-service | 长效记忆检索 Advisor |
| RequestResponseAdvisor.java | jia-chat-service | 请求响应处理 Advisor |
| ChatWebSocketHandler.java | jia-chat-service | WebSocket 处理器 |
| WebSocketConfig.java | jia-chat-service | WebSocket 配置 |
| StdioChatClientConfig.java | jia-chat-service | 命令行模式配置 |
| ChatMessageDTO.java | jia-chat-service | 聊天消息 DTO |

---

## 已修改文件

| 文件 | 说明 |
|------|------|
| ChatMessageDao.java | 新增按状态查询、批量更新方法 |
| DatabaseChatMemoryAdvisor | 保存消息后设置 sync_status=PENDING |
| ChatClientConfig | 集成 LongTermMemoryAdvisor + Agent Tools |
| ChatController | 完善流式响应和会话管理 |
| data.sql | INSERT 语句添加 sync_status 字段 |

---

## 实现状态

### ✅ 已完成功能

1. ✅ ChatClientConfig 配置 (Advisor + Tools)
2. ✅ LongTermMemoryAdvisor 长效记忆检索
3. ✅ DatabaseChatMemoryAdvisor 上下文记忆
4. ✅ LongTermMemoryService 接口及实现
5. ✅ SummaryGenerator 接口及实现
6. ✅ MemoryRepository 接口及实现
7. ✅ MemoryJobHandler 定时任务
8. ✅ ChatController HTTP API
9. ✅ ChatWebSocketHandler WebSocket
10. ✅ StdioChatClientConfig 命令行模式
11. ✅ 会话管理 (CRUD)
12. ✅ Redis 取消信号机制
13. ✅ 会话标题自动生成

### 🔄 进行中

1. Agent Tools 集成完善
2. Elasticsearch 索引自动创建

### 📋 待完善

1. 配置热更新支持
2. 向量生成降级方案优化

---

## 模块依赖

```groovy
dependencies {
    compileOnly project(':task:jia-task-core')
}
```

---

## 非目标

- 不提供前端手动管理接口
- 不实现复杂的记忆重要性评分系统
- 不修改现有 DatabaseChatMemoryAdvisor 行为（仅添加 sync_status 设置）
