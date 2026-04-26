# Chat 长效记忆向量库模块规格文档

## 模块概述

Chat 长效记忆向量库模块实现用户级别对话历史的自动向量化存储，记忆跨会话共享。通过定时任务定期汇总生成高质量记忆摘要，支持基于向量相似度的语义检索。

- **子模块**: core, service (扩展现有 chat 模块)
- **包名**: cn.jia.chat
- **版本**: 1.1.3-SNAPSHOT

## 技术实现

### 依赖组件

| 组件 | 说明 |
|------|------|
| ElasticsearchVectorStore | Spring AI 向量存储 (已在项目依赖中) |
| Spring AI Document | 文档抽象 |
| MyBatis Plus | 消息数据访问 |
| task 项目 | 定时任务管理 |

### 核心组件

#### LongTermMemoryService

```java
public interface LongTermMemoryService {
    
    /**
     * 同步会话消息到向量库
     * 对话结束后自动调用，将用户/助手消息写入向量库
     * 记忆属于用户(jiacn)，跨会话共享
     * @param jiacn 用户标识
     */
    void syncFromConversation(String jiacn);
    
    /**
     * 检索相似记忆
     * 按当前用户(jiacn)检索
     * @param request 检索请求
     * @return 相似记忆列表
     */
    List<MemoryResponse> search(MemorySearchRequest request);
    
    /**
     * 定时任务：汇总生成高质量记忆摘要
     * 每天凌晨执行，将近期对话汇总为结构化记忆
     * 由 task 项目统一调度
     */
    void generateDailySummary();
    
    /**
     * 定时任务：清理低价值记忆
     * 定期清理相似度过低或过时的记忆数据
     * 由 task 项目统一调度
     */
    void cleanupLowValueMemories();
    
    /**
     * 获取用户记忆统计
     * @param jiacn 用户标识
     * @return 统计信息
     */
    MemoryStats getStats(String jiacn);
}
```

### DTO 对象

#### MemorySearchRequest (检索记忆请求)

```java
@Data
@Accessors(chain = true)
@Schema(name = "MemorySearchRequest", description = "检索记忆请求")
public class MemorySearchRequest implements Serializable {
    @Schema(description = "检索文本", requiredMode = RequiredMode.REQUIRED)
    private String query;
    
    @Schema(description = "分类过滤 (knowledge/preference/event等)")
    private String category;
    
    @Schema(description = "返回数量", example = "5")
    private Integer topK;
    
    @Schema(description = "相似度阈值", example = "0.75")
    private Double threshold;
}
```

#### MemoryResponse (记忆响应)

```java
@Data
@Accessors(chain = true)
@Schema(name = "MemoryResponse", description = "记忆响应")
public class MemoryResponse implements Serializable {
    @Schema(description = "记忆ID")
    private String id;
    
    @Schema(description = "记忆内容")
    private String content;
    
    @Schema(description = "分类")
    private String category;
    
    @Schema(description = "重要性 (1-5)")
    private Integer importance;
    
    @Schema(description = "相似度分数")
    private Double similarity;
    
    @Schema(description = "创建时间戳")
    private Long timestamp;
}
```

#### MemoryStats (记忆统计)

```java
@Data
@Accessors(chain = true)
@Schema(name = "MemoryStats", description = "记忆统计")
public class MemoryStats implements Serializable {
    @Schema(description = "用户标识")
    private String jiacn;
    
    @Schema(description = "总记忆数")
    private Long totalCount;
    
    @Schema(description = "分类统计")
    private Map<String, Long> categoryStats;
    
    @Schema(description = "最后同步时间")
    private Long lastSyncTime;
    
    @Schema(description = "最后汇总时间")
    private Long lastSummaryTime;
}
```

### 向量库文档结构

```json
{
  "id": "uuid-string",
  "content": "用户说他们周末经常去跑步，喜欢户外运动",
  "metadata": {
    "jiacn": "用户标识",
    "category": "preference",
    "importance": 3,
    "timestamp": 1713792000000,
    "source": "conversation"
  }
}
```

**说明**: 文档不存储 conversationId，长效记忆属于用户级别，跨会话共享。

## 工作流程

### 1. 对话自动同步流程

```
用户提问 → AI回答 → 对话结束 
    ↓
自动触发 syncFromConversation(jiacn)
    ↓
查询该用户近期未同步的消息
    ↓
写入向量库 (按 jiacn 存储)
    ↓
设置 source="conversation"
    ↓
设置 importance=3 (默认值)
```

### 2. 定时汇总流程 (由 task 项目调度)

```
每天凌晨2点 task 项目触发
    ↓
调用 LongTermMemoryService.generateDailySummary()
    ↓
查询所有用户近期7天内的对话
    ↓
按用户分组，调用LLM生成结构化摘要
    ↓
写入向量库 (按 jiacn 存储)
    ↓
设置 source="summary", importance=4
```

### 3. 清理流程 (由 task 项目调度)

```
每周日凌晨 task 项目触发
    ↓
调用 LongTermMemoryService.cleanupLowValueMemories()
    ↓
按用户查询 source="conversation" 且 importance<3 的记录
    ↓
检查与 summary 的相似度
    ↓
删除冗余的原始对话记忆
```

## 定时任务集成

定时任务统一由 task 项目管理，在 `TaskSchedule` 中添加 chat 相关方法：

### 修改 TaskSchedule.java

```java
@Slf4j
@Component
public class TaskSchedule {
    
    @Autowired
    private TaskService taskService;
    // ... 现有注入 ...
    
    @Autowired(required = false)
    private LongTermMemoryService longTermMemoryService; // 可选依赖

    // ========== 现有任务 ==========
    @Scheduled(cron = "0 0/10 * * * ?")
    public void taskAlert() {
        // 现有任务到期通知逻辑...
    }

    // ========== Chat 长效记忆任务 ==========
    
    /**
     * 每日汇总生成高质量记忆摘要
     * 每天凌晨2点执行
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void memoryDailySummary() {
        if (longTermMemoryService == null) {
            log.debug("LongTermMemoryService not available, skip memory summary");
            return;
        }
        try {
            longTermMemoryService.generateDailySummary();
            log.info("Memory daily summary completed");
        } catch (Exception e) {
            log.error("Memory daily summary failed", e);
        }
    }
    
    /**
     * 清理低价值记忆
     * 每周日凌晨3点执行
     */
    @Scheduled(cron = "0 0 3 * * SUN")
    public void memoryCleanup() {
        if (longTermMemoryService == null) {
            log.debug("LongTermMemoryService not available, skip memory cleanup");
            return;
        }
        try {
            longTermMemoryService.cleanupLowValueMemories();
            log.info("Memory cleanup completed");
        } catch (Exception e) {
            log.error("Memory cleanup failed", e);
        }
    }
}
```

### 配置项

```properties
# 长效记忆配置
chat.memory.enabled=true
chat.memory.sync-enabled=true
chat.memory.retention-days=90
chat.memory.default-importance=3
chat.memory.summary-window-days=7
chat.memory.batch-size=100
```

## 模块影响分析

### 新增文件 (chat 模块)

| 文件 | 位置 | 说明 |
|------|------|------|
| LongTermMemoryService.java | jia-chat-api | 服务接口 |
| LongTermMemoryServiceImpl.java | jia-chat-service | 服务实现 |
| MemoryExtractionAdvisor.java | jia-chat-service | 对话结束自动同步 Advisor |
| MemorySearchRequest.java | jia-chat-core | 检索请求DTO |
| MemoryResponse.java | jia-chat-core | 响应DTO |
| MemoryStats.java | jia-chat-core | 统计DTO |

### 修改文件

| 文件 | 说明 |
|------|------|
| TaskSchedule.java (task 项目) | 添加 memoryDailySummary 和 memoryCleanup 方法 |
| ChatMessageDao.java | 新增按用户查询未同步消息的方法 |
| jia-chat-module-spec.md | 更新模块规格文档 |

## 非目标

- 不提供前端手动管理接口
- 不按会话隔离记忆（记忆属于用户级别）
- 不在 chat 模块内管理定时任务
- 不修改现有 QuestionAnswerAdvisor 行为
