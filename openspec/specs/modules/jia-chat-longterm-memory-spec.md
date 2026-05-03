# Chat 长效记忆模块规格文档

## 模块概述

Chat 长效记忆模块实现对话历史的智能记忆管理，通过 LLM 驱动的摘要生成机制，将对话内容提炼为结构化记忆，支持基于向量相似度的语义检索。

- **子模块**: core, service (扩展现有 chat 模块)
- **包名**: cn.jia.chat
- **版本**: 1.1.2-SNAPSHOT

## 设计原则

### 核心变更

**不采用全量消息同步到向量库**，而是通过摘要生成实现记忆提炼。

| 方案 | 优点 | 缺点 |
|------|------|------|
| 全量同步 | 实现简单 | 向量库膨胀、检索噪音大、存储成本高 |
| 摘要生成 | 提炼精华、减少冗余、支持结构化 | 需要 LLM 调用、摘要质量依赖模型 |

### 设计目标

1. **记忆提炼**：通过 LLM 生成结构化摘要，提取关键信息和用户偏好
2. **跨会话共享**：记忆属于用户级别，不同会话可检索相同记忆
3. **分层存储**：原始消息 → 摘要 → 高阶汇总，逐层提炼
4. **自动管理**：通过定时任务自动生成摘要和清理过期数据

## 系统架构

### 双层记忆架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           用户对话请求                                    │
└─────────────────────────────────────────────────────────────────────────┘
                                     │
                     ┌───────────────┴───────────────┐
                     ▼                               ▼
         ┌───────────────────────┐       ┌───────────────────────┐
         │    短期记忆 (数据库)     │       │    长期记忆 (向量库)     │
         │  DatabaseChatMemory    │       │  LongTermMemoryAdvisor │
         │                       │       │                       │
         │  • 会话级上下文          │       │  • 用户级记忆检索        │
         │  • 最近 N 条消息        │       │  • 向量相似度匹配        │
         │  • 按 conversationId   │       │  • 会话权重加权         │
         └───────────────────────┘       └───────────────────────┘
                     │                               │
                     └───────────────┬───────────────┘
                                     ▼
                         ┌───────────────────────┐
                         │      AI 模型响应       │
                         └───────────────────────┘
```

### 数据流向

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  用户消息    │ → │   AI 响应    │ → │  存储原始    │ → │  生成摘要   │
│  chat_message│    │  chat_message│    │    MySQL    │    │  sync       │
└─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘
                                                                │
                                                                ▼
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  定时汇总   │ ← │  日/周/月    │ ← │  向量嵌入    │ ← │  写入向量库  │
│  generate   │    │  汇总摘要    │    │  embedding  │    │  Elasticsearch │
└─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘
```

## 数据存储

### 原始消息 (MySQL)

**表**: chat_message

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint | 主键 |
| conversation_id | bigint | 会话ID |
| message_type | varchar | USER/ASSISTANT/SYSTEM |
| content | text | 消息内容 |
| jiacn | varchar | 用户标识 |
| sync_status | varchar | PENDING/SYNCED (已实现) |
| create_time | bigint | 创建时间 |

**保留策略**: 90 天，过期后归档或删除

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
  }
}
```

> **说明**: embedding 字段用于 KNN 向量检索，dims 根据配置 `chat.memory.embedding.dimension` 设置。

**保留策略**:

| 类型 | 保留期 | 说明 |
|------|--------|------|
| conversation | 90 天 | 会话级摘要 |
| daily_summary | 永久 | 日汇总（最近 30 条有效） |
| weekly_summary | 永久 | 周汇总（最近 12 条有效） |
| monthly_summary | 永久 | 月汇总（长期记忆） |

## 核心组件

### 1. 已实现组件

#### DatabaseChatMemoryAdvisor

**位置**: `cn.jia.chat.advisor.DatabaseChatMemoryAdvisor`

**功能**: 会话级上下文记忆

- `before`: 查询历史消息用于上下文增强，并保存当前用户消息
- `after`: 保存 AI 响应消息到数据库，设置 `sync_status = PENDING`

#### LongTermMemoryAdvisor (已实现)

**位置**: `cn.jia.chat.advisor.LongTermMemoryAdvisor`

**功能**: 长效记忆检索，支持会话ID优先级，并将检索结果注入到 AI 对话上下文

**实现方式**: 通过 MemoryRepository.searchWithConversationBoost() 实现，检索后应用权重

**Builder 用法**:
```java
LongTermMemoryAdvisor longTermMemoryAdvisor = LongTermMemoryAdvisor.builder(memoryRepository)
    .memoryTopK(5)
    .similarityThreshold(0.75)
    .build();
```

**检索策略**:

```
用户检索请求
    ↓
1. 从上下文获取当前会话ID (conversationId) 和 jiacn
    ↓
2. 生成查询向量 (简化实现，实际应调用 embedding 服务)
    ↓
3. 调用 MemoryRepository.searchWithConversationBoost()
    ├── KNN 检索: 向量相似度
    ├── 权重计算: 当前会话=1.5, 同一天=1.2, 其他=1.0
    ↓
4. 过滤低于 similarityThreshold 的结果
    ↓
5. 返回 topK 结果
```

### 检索结果使用流程

```
LongTermMemoryAdvisor 检索记忆
    ↓
格式化记忆内容为上下文文本 (带 token 限制)
    ↓
注入到 ChatModel 的 systemMessage
    ↓
AI 模型结合记忆上下文生成响应
    ↓
返回响应给用户
```

### 记忆上下文格式化

检索到记忆后，需要将内容格式化为可读上下文：

```java
private String formatMemoryContext(List<MemoryDocument> memories) {
    if (memories == null || memories.isEmpty()) {
        return "";
    }
    
    StringBuilder context = new StringBuilder();
    context.append("以下是与当前对话相关的历史记忆：\n\n");
    
    int totalChars = context.length();
    int maxChars = MAX_MEMORY_TOKENS * CHARS_PER_TOKEN;  // 2000 tokens
    
    for (int i = 0; i < memories.size(); i++) {
        MemoryDocument memory = memories.get(i);
        String memoryText = String.format("【记忆 %d】(%s)\n%s\n\n",
            i + 1, memory.getSummaryType(), memory.getContent());
        
        if (totalChars + memoryText.length() > maxChars) {
            break;
        }
        
        context.append(memoryText);
        totalChars += memoryText.length();
    }
    
    context.append("请结合以上记忆信息回答用户的问题。");
    return context.toString();
}
```

### 与 AI 模型集成

通过 `ChatClientConfig` 配置记忆上下文的注入方式：

```java
@Configuration
public class ChatClientConfig {
    
    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder,
            List<McpSyncClient> mcpSyncClients, VectorStore vectorStore,
            ChatMessageDao chatMessageDao, MemoryRepository memoryRepository) {
        
        // 使用 LongTermMemoryAdvisor 实现长效记忆检索(带会话权重)
        LongTermMemoryAdvisor longTermMemoryAdvisor = LongTermMemoryAdvisor.builder(memoryRepository)
                .memoryTopK(5)
                .similarityThreshold(0.75)
                .build();
        
        // 使用 DatabaseChatMemoryAdvisor 实现上下文记忆
        DatabaseChatMemoryAdvisor contextMemoryAdvisor = DatabaseChatMemoryAdvisor.builder(chatMessageDao)
                .maxMessages(10)
                .build();
        
        return chatClientBuilder
                .defaultAdvisors(longTermMemoryAdvisor)
                .defaultAdvisors(contextMemoryAdvisor)
                .defaultAdvisors(SimpleLoggerAdvisor.builder().build())
                .build();
    }
}
```

### Advisor 执行顺序

| 顺序 | Advisor | Order | 功能 |
|------|---------|-------|------|
| 1 | LongTermMemoryAdvisor | DEFAULT + 100 | 检索跨会话长效记忆 |
| 2 | DatabaseChatMemoryAdvisor | DEFAULT | 检索当前会话短期消息 |
| 3 | SimpleLoggerAdvisor | - | 日志记录 |

### 记忆内容结构

记忆文档包含以下字段，可在格式化时选择使用：

| 字段 | 说明 | 使用场景 |
|------|------|----------|
| content | 记忆内容/摘要 | 主要显示内容 |
| topic | 话题标签 | 快速判断相关性 |
| summary_type | 摘要类型 | conversation/daily_summary/weekly_summary |
| timestamp | 时间戳 | 判断时效性 |
| conversation_id | 来源会话 | 避免重复引用同一会话 |
| score | 检索得分 | 排序和过滤 |

### 上下文长度控制

为避免超出 AI 模型的最大 token 限制，需要控制记忆上下文长度：

- `MAX_MEMORY_TOKENS = 2000` (默认值)
- `CHARS_PER_TOKEN = 4` (估算值)
- 超过限制时提前终止

### 与短期记忆的配合

长效记忆（LongTermMemoryAdvisor）和短期记忆（DatabaseChatMemoryAdvisor）协同工作：

| 记忆类型 | 来源 | 用途 | 时效性 |
|----------|------|------|--------|
| 短期记忆 | MySQL 最近 N 条消息 | 当前会话上下文 | 会话内有效 |
| 长期记忆 | Elasticsearch | 跨会话知识检索 | 持久有效 |

**完整对话流程**:

```
用户提问
    ↓
[LongTermMemoryAdvisor] 检索跨会话长效记忆
    ↓
[formatMemoryContext] 格式化记忆为上下文文本
    ↓
[DatabaseChatMemoryAdvisor] 查询当前会话短期消息作为上下文
    ↓
合并系统提示: base_system + memory_context + session_context
    ↓
[AI 模型] 结合所有上下文生成响应
    ↓
[DatabaseChatMemoryAdvisor] 保存 AI 响应到数据库 (sync_status=PENDING)
    ↓
返回响应给用户
```

### 2. 已实现服务组件

#### LongTermMemoryService

**位置**: `cn.jia.chat.service.LongTermMemoryService`

**接口定义**:

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

**实现类**: `cn.jia.chat.service.impl.LongTermMemoryServiceImpl`

#### SummaryGenerator

**位置**: `cn.jia.chat.service.SummaryGenerator`

**职责**: 调用 LLM 生成结构化摘要

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

**实现类**: `cn.jia.chat.service.impl.DefaultSummaryGenerator`

使用 Spring AI ChatClient 调用 LLM：

```java
String content = chatClient.prompt(promptText).call().content();
```

#### MemoryJobHandler

**位置**: `cn.jia.chat.job.MemoryJobHandler`

**实现方式**: 实现 `JobHandler` 接口，通过 task 项目的调度能力执行

```java
@Slf4j
@Component
public class MemoryJobHandler implements JobHandler {

    private static final String GROUP = "chat";

    @Autowired
    private LongTermMemoryService longTermMemoryService;

    @Override
    public String getName() {
        return "memory_sync";
    }

    @Override
    public String getDescription() {
        return "对话记忆同步和汇总";
    }

    @Override
    public String getGroup() {
        return GROUP;
    }

    @Override
    public String getCron() {
        return "0 0 * * * ?"; // 每小时执行
    }

    @Override
    public String execute(JobContext context) throws Exception {
        log.info("Starting memory sync job");
        longTermMemoryService.syncPendingConversations();
        log.info("Memory sync job completed");
        return "success";
    }
}
```

## 工作流程

### 1. 对话流程 (已实现)

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

### 2. 同步流程 (已实现)

```
[定时任务] 每小时触发
    ↓
查询 sync_status = 'PENDING' 的会话
    ↓
批量处理会话列表 (每批 100 个)
    ↓
对每个会话:
    ├── 获取完整消息列表
    ├── 调用 LLM 生成摘要 (DefaultSummaryGenerator)
    ├── 写入向量库 (summary_type = 'conversation')
    └── 更新 sync_status = 'SYNCED'
```

### 3. 日汇总流程 (已实现)

```
[定时任务] 每天凌晨2点触发
    ↓
查询所有活跃用户 (近7天有对话)
    ↓
对每个用户:
    ├── 获取近7天的会话摘要列表
    ├── 调用 LLM 生成日汇总
    ├── 写入向量库 (summary_type = 'daily_summary')
    └── 清理旧的日汇总 (保留最近30条)
```

### 4. 清理流程 (已实现)

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

## 配置项

```properties
# 批处理
chat.memory.batch-size=100

# 清理策略
chat.memory.retention-days=90

# 向量维度 (与 embedding 服务匹配)
chat.memory.embedding.dimension=1024

# 检索权重
chat.memory.retrieval.current-conversation-weight=1.5
chat.memory.retrieval.same-day-weight=1.2

# 定时任务 (可配置)
chat.memory.job.sync-cron=0 0 * * * ?
chat.memory.job.daily-summary-hour=2
chat.memory.job.weekly-summary-hour=3
chat.memory.job.monthly-summary-hour=4

# 汇总窗口配置
chat.memory.summary.daily-window-days=7
chat.memory.summary.daily-summary-keep=30
chat.memory.summary.weekly-lookback-days=30
chat.memory.summary.monthly-lookback-days=90
```

## 模块依赖

**新增依赖**:

| 模块 | 依赖类型 | 说明 |
|------|----------|------|
| jia-chat-service | compileOnly | jia-task-core (JobHandler 接口) |

**gradle 配置**:

```groovy
dependencies {
    compileOnly project(':task:jia-task-core')
}
```

## 模块影响分析

### 已新增文件

| 文件 | 位置 | 说明 |
|------|------|------|
| LongTermMemoryService.java | jia-chat-api | 服务接口 |
| LongTermMemoryServiceImpl.java | jia-chat-service | 服务实现 |
| SummaryGenerator.java | jia-chat-api | 摘要生成接口 |
| DefaultSummaryGenerator.java | jia-chat-service | LLM 摘要生成实现 |
| MemoryJobHandler.java | jia-chat-service | 定时任务处理器 (实现 JobHandler) |
| MemoryRepository.java | jia-chat-service | 向量存储库接口 |
| MemoryRepositoryImpl.java | jia-chat-service | ES 操作封装 (支持 KNN + 会话权重) |
| MemoryDocument.java | jia-chat-service | 记忆文档模型 |
| LongTermMemoryAdvisor.java | jia-chat-service | 长效记忆检索 Advisor (带 Builder) |

### 已修改文件

| 文件 | 说明 |
|------|------|
| ChatMessageDao.java | 新增按状态查询、批量更新方法 |
| DatabaseChatMemoryAdvisor | 保存消息后设置 sync_status=PENDING |
| ChatClientConfig | 配置 LongTermMemoryAdvisor |
| data.sql | INSERT 语句添加 sync_status 字段 |

### sync_status 设置时机

在 `DatabaseChatMemoryAdvisor` 的 `saveMessage` 方法中：

```java
// 保存消息时，设置同步状态为 PENDING
entity.setSyncStatus("PENDING");
chatMessageDao.insert(entity);
```

## 技术要点

### 1. MemoryRepository 接口设计

```java
public interface MemoryRepository {
    void save(MemoryDocument document);
    void saveAll(List<MemoryDocument> documents);
    
    /**
     * 搜索记忆 (带会话权重)
     * @param jiacn 用户标识
     * @param userMessage 用户消息（用于生成查询向量）
     * @param currentConversationId 当前会话ID
     * @param topK 返回数量
     * @param similarityThreshold 相似度阈值，低于此值的结果会被过滤
     * @return 记忆文档列表
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

### 2. LongTermMemoryAdvisor Builder 模式

```java
public static class Builder {
    private final MemoryRepository memoryRepository;
    private boolean memoryEnabled = true;
    private int memoryTopK = 5;
    private double similarityThreshold = 0.75;
    private String defaultSystemPrompt = "你是一个有帮助的AI助手。";

    public Builder(MemoryRepository memoryRepository) { ... }
    public Builder memoryEnabled(boolean memoryEnabled) { ... }
    public Builder memoryTopK(int memoryTopK) { ... }
    public Builder similarityThreshold(double similarityThreshold) { ... }
    public LongTermMemoryAdvisor build() { ... }
}
```

### 3. 错误处理

LongTermMemoryServiceImpl 中包含重试机制：

```java
private static final int MAX_RETRY = 3;
private static final long RETRY_INTERVAL = 5000; // 5秒
```

## 定时任务明细

统一由 `MemoryJobHandler` 调度，支持配置：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| chat.memory.job.sync-cron | `0 0 * * * ?` | 会话同步 Cron 表达式 |
| chat.memory.job.daily-summary-hour | `2` | 日汇总执行小时 (0-23) |
| chat.memory.job.weekly-summary-hour | `3` | 周汇总执行小时 (0-23) |
| chat.memory.job.monthly-summary-hour | `4` | 月汇总执行小时 (0-23) |

> **说明**：
> - 统一使用一个 JobHandler，通过 Cron 触发后在内部判断执行哪个任务
> - 周汇总仅在周日执行
> - 月汇总仅在每月1日执行
| 任务 | JobHandler | Cron | 说明 |
|------|------------|------|------|
| 会话同步 | memory_sync | 可配置 (默认每小时) | 同步待处理会话 |
| 日汇总 | memory_sync | 在配置的小时执行 | 每天凌晨生成日汇总 |
| 周汇总+清理 | memory_sync | 在配置的小时执行 (周日) | 每周生成周汇总 |
| 月汇总 | memory_sync | 在配置的小时执行 (每月1日) | 每月生成月汇总 |

## 非目标

- 不提供前端手动管理接口
- 不实现复杂的记忆重要性评分系统
- 不修改现有 DatabaseChatMemoryAdvisor 行为（仅添加 sync_status 设置）

## 实现状态

### 已完成功能

1. ✅ LongTermMemoryService 接口及实现
2. ✅ SummaryGenerator 接口及 DefaultSummaryGenerator 实现
3. ✅ MemoryRepository 接口及 MemoryRepositoryImpl 实现
4. ✅ MemoryJobHandler 定时任务处理器
5. ✅ LongTermMemoryAdvisor 长效记忆检索（带 Builder 模式）
6. ✅ DatabaseChatMemoryAdvisor 设置 sync_status=PENDING
7. ✅ ChatClientConfig 集成 LongTermMemoryAdvisor
8. ✅ ChatMessageDao 接口扩展（新增 5 个方法）
9. ✅ 数据库 schema 和 data 更新（sync_status 字段）
10. ✅ 会话权重检索（当前会话 1.5x，同一天 1.2x）
11. ✅ 记忆上下文格式化（带 token 限制）
12. ✅ 日/周/月汇总生成
13. ✅ 过期数据清理

### 待完善功能

1. Elasticsearch 索引自动创建
2. 配置热更新支持

### 向量生成方式

使用 Spring AI `EmbeddingModel` 生成向量向量：

```java
@Autowired
private EmbeddingModel embeddingModel;

private float[] generateEmbeddingArray(String text) {
    // 使用 EmbeddingModel 生成真实嵌入向量
    float[] embedding = embeddingModel.embed(text);
    return embedding;
}
```

**降级方案**：如果 EmbeddingModel 调用失败，使用 hash 作为种子生成伪向量。
