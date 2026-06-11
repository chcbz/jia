# 聚义厅 - AI Agent 协作模块规格说明书

> **文档状态**：草稿 v0.9
> **创建日期**：2025年
> **最后更新**：2026-06-11
> **目标读者**：产品团队、前端开发、后端开发
> **版本**：MVP
> **依赖关系**：基于 jia-api-project-spec.md、jia-chat-longterm-memory-spec.md 和 cyf-web-kit 聚义厅前端规格扩展

---

## 2026-06-07 后端优化完成度与验证记录

已完成：

- Chat 会话与消息实体已补齐 `conversation_type`、`sender_type`、`sender_name` 等字段映射，支撑聚义厅会话与 Agent/系统发送者展示。
- OpenClaw Channel WebSocket 已支持 `chat`、`chat.stream`、`stop`、`chat.stop`，并修复事件返回包装顺序。
- Agent 任务 DTO 已补齐悬赏金额、创建/分配/开始/完成时间与失败原因等字段。
- 已启用 Agent 心跳超时自动离线监控，新增 `AgentStatusMonitor` 并在启动类启用调度。
- 已新增 `HallAnnouncementService`，Agent 状态与任务事件可持久化为聚义厅系统消息。
- 能力评估一期已落地实体、DAO、Mapper、Service、Controller 与 SQL 初始化文件，提供 `/agent/evaluate`、`/agent/compare`、`/agent/evaluation/*` 等接口。
- 灰度配置模型已调整为 `spring.ai.openai.chat.options.model=gpt5.4`。

验证结果：

- `.\gradlew.bat :starter:compileJava :chat:jia-chat-service:test :agent:jia-agent-service:test` 通过。
- 前端已能通过构建并在浏览器进入 `https://localhost:8080/juyiting` 验证聚义厅页面与聊天弹窗。

当前阻塞：

- 后端灰度环境已确认本地 Jasypt 解密口令：`cyf0519`。
- 本地启动命令：`.\gradlew.bat :starter:bootRun --args="--spring.profiles.active=grey --jasypt.encryptor.password=cyf0519"`。
- 2026-06-11 验证结果：`https://localhost:10018` 可监听，日志出现 `Started JiaApplication`；未登录访问 `/agent/map`、`/agent/roster` 返回 `401` 属于认证拦截。

后续建议：

- 为能力评估接口补充专门单元测试/集成测试，覆盖评分、对比、最新记录、统计与删除路径。
- 继续完成真实 OpenClaw Agent 连接、`@Agent` 消息投递与前端回显的端到端验证。

---

## 1. 项目概述

### 1.1 项目背景

聚义厅是一个水浒传题材的 AI Agent 协作模块，灵感来源于《水浒传》中梁山泊好汉聚义的场景。通过拟人化的方式管理 AI 子代理（Sub-Agent），赋予它们独特的"水浒人物"个性，让任务分配和协作过程更加生动有趣。

> **注意**：本模块是在 Jia 后端微服务框架内实现的后端服务模块，非前端管理界面。

### 1.2 与现有项目的关系

聚义厅模块是对现有 Jia 微服务框架的功能扩展：

| 现有模块 | 关系 | 说明 |
|---------|------|------|
| Chat (chat) | 复用 | 基于 ChatController 实现 Agent 间通信，复用 ChatClient 配置 |
| Task (task) | 复用 | 使用现有任务调度系统管理 Agent 任务 |
| 通用 (common) | 复用 | 使用统一的 JsonResult、BaseEntity 等组件 |
| Agent Tools | 复用 | 基于 spring-ai-agent-utils 的 TaskTool、SkillsTool |

### 1.2.1 与前端聚义厅规格的对齐

前端 `cyf-web-kit` 将聚义厅定义为“Agent 管理 + 悬赏榜 + 聚义厅聊天”的组合页面，后端设计需要提供稳定的数据契约，而不是只提供人设配置。

| 前端视图/能力 | 后端支撑能力 | 契约重点 |
|------|------|------|
| Agent 列表 | Agent 注册表 + 状态快照 | `online/busy/offline/error` 状态、能力标签、当前任务摘要 |
| Agent 详情弹窗 | Agent 详情 API + 统计 API | 基础资料、能力、最近任务、成功/失败统计 |
| 聚义厅聊天 | Chat 会话 + OpenClaw Channel | `conversationType=juyiting`、Agent 发送者、系统公告 |
| 悬赏榜 | Task 模块 + AgentTaskMeta | 任务状态映射、所需能力、分配 Agent、奖励字段 |
| 状态同步 | 轮询 + WebSocket 事件 | MVP 支持 HTTP 拉取，WebSocket 作为实时增强 |

前端不直接理解后端内部的 `TaskTool`、`ClaudeSubagentType` 和长效记忆实现；后端必须将这些内部能力转换成前端可消费的 Agent、任务、消息和事件模型。

### 1.3 核心价值

- **任务编排可视化**：通过日志和事件机制追踪 Agent 子代理的任务执行状态
- **水浒角色定制**：为 AI 子代理配置水浒人物人设，提升交互趣味性
- **协作能力增强**：基于现有 Chat 模块实现 Agent 间的协作与通信
- **记忆共享**：复用 LongTermMemoryAdvisor 实现 Agent 间的记忆共享
- **前后端契约清晰**：对齐前端 Agent 管理、悬赏榜和聊天视图，减少页面侧临时适配

### 1.4 关键概念

| 概念 | 说明 |
|------|------|
| **聚义厅** | AI Agent 协作的公共区域，对应 ChatController 会话空间 |
| **Agent (子代理)** | 可注册、可调度、可上报状态的任务执行单元；内部可由 TaskTool/ClaudeSubagentType 承载 |
| **水浒人设** | 为 Agent 配置的水浒人物名称、台词风格、性格特点 |
| **协作任务** | 通过子代理编排实现的多 Agent 协作任务 |
| **悬赏任务** | 面向 Agent 的任务，按待领取、已分配、执行中、完成、失败展示 |
| **系统公告** | Agent 上线、下线、任务分配、任务完成等非用户输入消息 |

### 1.5 架构定位

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          Jia 微服务框架                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌─────────────────────────────┐    ┌─────────────────────────────────┐│
│  │      聚义厅模块 (本模块)       │    │      Chat 模块 (复用)            ││
│  │  ┌───────────────────────┐  │    │  ┌───────────────────────────┐   ││
│  │  │  Agent 注册与管理      │  │    │  │  ChatClient + Advisors    │   ││
│  │  │  水浒人设配置          │  │◄──►│  │  LongTermMemoryAdvisor   │   ││
│  │  │  协作任务编排          │  │    │  │  DatabaseChatMemoryAdv   │   ││
│  │  └───────────────────────┘  │    │  └───────────────────────────┘   ││
│  └─────────────────────────────┘    └─────────────────────────────────┘│
│                                                                         │
│  ┌─────────────────────────────┐    ┌─────────────────────────────────┐│
│  │    Agent Tools (复用)        │    │      Task 模块 (复用)            ││
│  │  ┌───────────────────────┐  │    │  ┌───────────────────────────┐   ││
│  │  │  TaskTool            │  │    │  │  JobHandler               │   ││
│  │  │  SkillsTool          │  │    │  │  定时任务调度              │   ││
│  │  │  ClaudeSubagentType  │  │    │  │                           │   ││
│  │  └───────────────────────┘  │    │  └───────────────────────────┘   ││
│  └─────────────────────────────┘    └─────────────────────────────────┘│
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 2. 功能需求

### 2.1 功能边界

本模块在 Jia 后端微服务框架内实现以下功能：

| 功能 | 范围 | 复用/新建 |
|------|------|----------|
| Agent 注册与状态管理 | 新建 | 支持前端列表、详情和状态同步 |
| 水浒人设配置 | 新建 | - |
| Agent 间通信 | 复用 | Chat 模块 |
| 悬赏任务和任务分配 | 复用 + 扩展 | Task 模块 + AgentTaskMeta |
| 记忆共享 | 复用 | LongTermMemory 模块 |
| MCP 工具集成 | 复用 | spring-ai-agent-utils |
| OpenClaw Channel | 新建 | WebSocket 事件通道 |

**不包含**：
- 前端管理界面（由外部前端项目实现）
- Agent 客户端 SDK（由外部提供）
- 自动抢单、复杂派单策略和工作流编排（后续可复用 workflow 模块）

### 2.2 前端协作链路优化（2026-06-07）

现有聚义厅前端的世界观表达已经成立，但高频调度链路存在入口分散、上下文隐藏和任务指派不闭环的问题。本轮规格将前端协作体验收敛为一条主路径：

```text
选好汉 -> 看悬赏 -> 发传令 -> 指派/回报
```

#### 2.2.1 用户建议评估结论

| 建议 | 结论 | 规格处理 |
|------|------|----------|
| 启用常驻操作栏，减少重复入口 | 采纳 | `BottomDock` 或等价常驻操作栏成为主操作入口；顶部图标、地图房间和热点保留为辅助入口 |
| 固定展示当前好汉和当前任务 | 采纳 | 页面底部或右下角必须展示“双上下文锁定”卡片，并同步到聊天与指派动作 |
| 悬赏榜推荐 Agent 行内直接 `选中/指派/传令` | 采纳 | 前端必须支持 `assignTask(task, agent)` 显式指派，不再依赖隐藏的 `selectedAgent` |
| 聊天面板强化对象、任务、连接状态和快捷模板 | 采纳 | 聊天顶部展示上下文，输入区提供 `汇报状态`、`评估风险`、`接令确认` 三类模板 |
| 大厅只展示前 12 个 Agent 的问题 | 调整采纳 | 舞台继续展示高活跃 Agent，但必须显示“另有 N 位在偏厅候命”，名册提供筛选 |
| 拆分 `JuyiHall.vue` | 采纳但分阶段 | 先完成体验闭环，再拆 `HallStage.vue`、`useHallConversation.js`、`useHallData.js`，避免体验改造和重构互相放大风险 |
| 实时推送替代主动刷新、能力评估报告 | 暂缓 | 作为后续增强，不纳入本轮前端主链路优化的验收门槛 |

#### 2.2.2 主操作入口

- 聚义厅页面必须有一个常驻主操作栏，至少包含 `名册`、`悬赏`、`传令` 三个入口。
- 常驻操作栏必须显示当前激活面板，并在移动端保持可触达；如果空间不足，允许收敛为底部抽屉或浮动按钮组。
- 顶部图标、地图房间和场景热点是辅助入口，不得成为唯一可发现的主路径。
- 操作栏应展示聚义厅关键摘要：在线好汉数、悬赏数、当前选中好汉、当前选中任务。

#### 2.2.3 双上下文锁定

前端必须维护两个显式上下文：

| 上下文 | 字段 | 作用 |
|------|------|------|
| 当前好汉 | `selectedAgentId`、`selectedAgentName`、`status`、`abilities` | 聊天定向、任务指派、详情查看 |
| 当前任务 | `selectedTaskId`、`selectedTaskTitle`、`status`、`requiredAbilities` | 聊天议事、悬赏详情、任务指派 |

上下文展示要求：

- 未选择时显示明确空态，例如“未选好汉”“未选悬赏”，不能让按钮静默失效。
- 选择推荐 Agent 时必须同步更新当前好汉卡；选择悬赏时必须同步更新当前任务卡。
- 发传令时，请求 `metadata` 必须带上 `selectedAgentId`、`mentionAgentIds`、`selectedTaskId`，没有对应上下文时字段可为空但键名应保持稳定。
- 关闭当前好汉或当前任务时，只清理对应上下文，不应重置另一个上下文。

---

## 3. Agent 管理

### 3.1 Agent 注册

Agent 注册分为两类：

| 类型 | 来源 | 用途 |
|------|------|------|
| 人设注册 | 配置或数据库初始化 | 提供水浒角色、头像、台词和默认能力 |
| 运行时注册 | HTTP API 或 OpenClaw Channel 握手 | 提供真实可调度 Agent 的在线状态、endpoint、token 和能力声明 |

运行时注册接口用于前端 Agent 列表和外部 OpenClaw 客户端接入：

```http
POST /agent/register
```

请求体：

```json
{
  "agentId": "agent-001",
  "name": "吴用",
  "avatar": "https://example.com/avatar/wuyong.png",
  "abilities": ["planning", "research", "code-review"],
  "endpoint": "wss://example.com/agent/agent-001",
  "personaName": "吴用"
}
```

响应体：

```json
{
  "code": "E0",
  "status": 200,
  "data": {
    "agentId": "agent-001",
    "token": "agent-token",
    "status": "online"
  }
}
```

内部 TaskTool 子代理仍可通过 ChatClient 配置注册：

```java
// ChatClientConfig.java 中的配置
@Value("${chat.agent.skills.dirs:}")
private String[] skillsRootDirectories;

// TaskTool 配置
var taskTool = TaskTool.builder()
        .subagentTypes(ClaudeSubagentType.builder()
                .chatClientBuilder("default", chatClientBuilder.clone())
                .skillsDirectories(List.of(skillsRootDirectories))
                .build())
        .build();
```

### 3.2 Agent 水浒人设配置

通过配置文件定义 Agent 的水浒人物人设：

```properties
# Agent 人设配置
jia.agent.personas=宋江,吴用,李逵,武松,鲁智深

# 每个 Agent 的详细配置
jia.agent.persona.宋江.name=宋江
jia.agent.persona.宋江.title=梁山泊寨主
jia.agent.persona.宋江.avatar=/images/agents/songjiang.png
jia.agent.persona.宋江.abilities=领导力,谈判,战略规划

jia.agent.persona.吴用.name=吴用
jia.agent.persona.吴用.title=军师
jia.agent.persona.吴用.avatar=/images/agents/wuyong.png
jia.agent.persona.吴用.abilities=智谋,策划,分析
```

### 3.3 水浒风格台词系统

```java
@Data
public class AgentPersona {
    private String name;              // 水浒人物名称
    private String title;             // 称号
    private String avatar;            // 头像URL
    private List<String> abilities;   // 能力列表
    private List<String> idleDialogues;  // 空闲时的台词
    private List<String> workingDialogues;  // 工作时的台词
    private List<String> completionDialogues;  // 完成任务的台词
    
    // 获取随机台词
    public String getRandomDialogue(DialogueType type) {
        List<String> dialogues = switch (type) {
            case IDLE -> idleDialogues;
            case WORKING -> workingDialogues;
            case COMPLETION -> completionDialogues;
        };
        return dialogues.get(new Random().nextInt(dialogues.size()));
    }
}
```

### 3.4 Agent 状态

| 状态 | 描述 | 使用场景 |
|------|------|----------|
| `online` | 在线，可接收任务 | Agent 已注册且最近心跳正常 |
| `busy` | 正在执行任务 | Agent 有当前任务或 TaskTool 子代理运行中 |
| `offline` | 离线或不可用 | 心跳超时、主动下线或 endpoint 不可达 |
| `error` | 状态异常 | Agent 上报错误或任务执行异常 |

内部执行状态映射：

| 内部状态 | 对外状态 | 说明 |
|------|------|------|
| `idle` | `online` | 空闲但可接收任务 |
| `working` | `busy` | 正在处理任务 |
| `completed` | `online` | 完成后回到可接收任务状态 |
| `failed` | `error` | 失败后保留错误信息，等待人工或自动恢复 |

### 3.5 Agent 状态同步

MVP 支持 HTTP 轮询，实时增强使用 WebSocket 事件。

HTTP 查询：

```http
GET /agent/list?status=online&ability=code-review
GET /agent/{agentId}
```

WebSocket 下行事件：

```json
{
  "type": "agent_status",
  "channel": "openclaw",
  "agentId": "agent-001",
  "status": "busy",
  "currentTask": {
    "id": "task-001",
    "title": "分析订单模块异常"
  },
  "errorMessage": null,
  "updatedAt": 1780071727937
}
```

前端处理约定：

- 收到未知 `agentId` 时，前端应触发一次 `/agent/list` 刷新。
- 收到 `busy` 且包含 `currentTask` 时，Agent 卡片展示任务标题。
- 收到 `offline` 时保留 Agent 基础资料，但清空当前任务。
- 收到 `error` 时详情弹窗展示 `errorMessage`，并禁用任务分配入口。

---

## 4. 聚义厅通信（复用 Chat 模块）

### 4.1 功能说明

基于现有 ChatController 实现 Agent 间通信，Agent 通过会话来交流和协作。

### 4.2 复用现有组件

| 组件 | 文件 | 说明 |
|------|------|------|
| ChatController | `cn.jia.chat.api.ChatController` | HTTP 聊天接口 |
| ChatWebSocketHandler | `cn.jia.chat.handler.ChatWebSocketHandler` | WebSocket 聊天 |
| LongTermMemoryAdvisor | `cn.jia.chat.advisor.LongTermMemoryAdvisor` | 长效记忆检索 |
| DatabaseChatMemoryAdvisor | `cn.jia.chat.advisor.DatabaseChatMemoryAdvisor` | 上下文记忆 |

### 4.3 扩展点

- **Agent 间消息**：Agent 通过指定 conversationId 进行通信
- **记忆共享**：通过 LongTermMemoryAdvisor 实现跨 Agent 记忆共享
- **@提及**：支持在消息中 @指定 Agent 进行定向通信
- **系统公告**：Agent 状态变化、任务分配和任务完成以系统消息写入聚义厅会话
- **聚义厅会话类型**：ChatConversation 建议增加或约定 `conversationType=juyiting`

### 4.3.1 聚义厅聊天消息约定

聚义厅聊天复用 `/chat/stream` 和会话接口，但请求和消息元数据需要带上场景字段，便于前端复用现有 Chat 组件时筛选会话和渲染发送者。

```json
{
  "conversationId": "hall-main",
  "conversationType": "juyiting",
  "content": "@吴用 请拆解这个任务",
  "senderType": "user",
  "senderId": "admin-001"
}
```

消息展示字段：

| 字段 | 说明 |
|------|------|
| `conversationType` | 固定为 `juyiting` 时进入聚义厅会话列表 |
| `senderType` | `user`、`agent`、`system` |
| `senderId` | 用户 ID、Agent ID 或系统事件来源 |
| `senderName` | 前端显示名称，可由 Agent 资料补齐 |
| `mentionAgentIds` | `@Agent` 解析结果，MVP 可为空数组 |
| `eventType` | 系统公告类型，如 `agent_online`、`task_assigned`、`task_completed` |

如果 Chat 模块短期不扩展 DTO，MVP 可将这些字段放入 ChatMessageEntity 的 `metadata` JSON 中。

### 4.3.2 聚义厅传令面板交互契约

聚义厅传令面板必须让用户在发送前看见“对谁说、围绕什么任务说、当前连接是否可用”。

| 区域 | 必须展示/支持 | 说明 |
|------|---------------|------|
| 顶部上下文 | 当前对象、当前任务、连接/同步状态 | 当前对象来自 `selectedAgent` 或 `@Agent`；当前任务来自 `selectedTask` |
| 快捷模板 | `汇报状态`、`评估风险`、`接令确认` | 点击后向输入框插入带上下文的文本，不直接发送 |
| 消息分层 | 用户消息、Agent 消息、系统公告、流式回复 | 使用稳定 class 或 message type，便于样式和测试识别 |
| @Agent 区 | 允许快速选择目标 Agent | 选择后同步 `selectedAgent`，并更新 `mentionAgentIds` |

快捷模板示例：

| 模板 | 输入内容规则 |
|------|--------------|
| `汇报状态` | `请汇报「{selectedTaskTitle}」当前进展、阻塞点和下一步。` |
| `评估风险` | `请评估「{selectedTaskTitle}」的主要风险、依赖和可回滚方案。` |
| `接令确认` | `请确认是否接令「{selectedTaskTitle}」，并说明预计完成方式。` |

当未选择任务时，模板中的任务名使用“当前议题”；当未选择 Agent 时，模板面向“全体好汉”。

### 4.4 OpenClaw Channel WebSocket 服务端

Chat 模块提供面向 OpenClaw Channel 的 WebSocket 服务端入口，用于外部 OpenClaw 客户端建立长连接并以 JSON 事件方式驱动聚义厅会话。

| 能力 | 说明 |
|------|------|
| `chat.stream` | 接收 OpenClaw 的聊天请求，复用 ChatClient、DatabaseChatMemoryAdvisor 和 LongTermMemoryAdvisor，按 token/chunk 流式返回 |
| `chat.stop` | 根据 requestId 或 conversationId 停止当前连接上的流式任务 |
| `ping` | 连接保活，返回 `pong` 事件 |
| `agent.register` | OpenClaw 客户端通过 WebSocket 注册运行时 Agent |
| `agent.status` | OpenClaw 客户端上报状态；服务端也下发 Agent 状态变化事件，供前端实时更新卡片 |
| `task.assign` | OpenClaw 客户端通过 WebSocket 发起任务分配 |
| `task.report` | OpenClaw 客户端通过 WebSocket 上报任务运行、完成或失败 |
| `task.event` | 下发悬赏任务分配、开始、完成、失败事件 |

#### 4.4.1 连接地址

```
ws://{host}:{port}/ws/agent/channel
```

服务端与原 `/ws/chat` 共用开关：

```properties
jia.chat.service.websocket.enable=true
jia.chat.service.websocket.allowed-origin-patterns=*
```

#### 4.4.2 上行消息

OpenClaw Channel 上行消息统一使用 JSON 对象：

```json
{
  "type": "chat",
  "requestId": "req-001",
  "conversationId": "conv-001",
  "content": "请分配合适的子代理分析这个任务",
  "agent": "宋江",
  "jiacn": "user-001",
  "clientId": "openclaw"
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `type` | 否 | 消息类型，默认 `chat`；可选 `chat`、`stop`、`ping`、`agent.register`、`agent.status`、`task.assign`、`task.report` |
| `requestId` | 否 | 请求追踪 ID，同一连接内用于停止指定流 |
| `conversationId` | 否 | 会话 ID；为空时服务端以 WebSocket sessionId 作为默认会话 |
| `content` | chat 必填 | 用户或 Agent 输入内容 |
| `agent` | 否 | 发起消息的 Agent 名称，用于事件追踪 |
| `jiacn` | 否 | 记忆检索和消息归属用户标识 |
| `clientId` | 否 | 客户端标识，OpenClaw 默认使用 `openclaw` |

停止当前流：

```json
{
  "type": "stop",
  "requestId": "req-001",
  "conversationId": "conv-001"
}
```

保活：

```json
{
  "type": "ping",
  "requestId": "hb-001"
}
```

Agent 注册：

```json
{
  "type": "agent.register",
  "requestId": "agent-reg-001",
  "agentId": "agent-001",
  "name": "吴用",
  "abilities": ["planning", "analysis"],
  "endpoint": "ws://localhost:10121/ws/agent/channel"
}
```

任务分配：

```json
{
  "type": "task.assign",
  "requestId": "assign-001",
  "taskId": "task-001",
  "agentId": "agent-001",
  "allowQueue": false
}
```

任务上报：

```json
{
  "type": "task.report",
  "requestId": "report-001",
  "taskId": "task-001",
  "agentId": "agent-001",
  "status": "running",
  "currentTaskTitle": "分析 OpenClaw Channel"
}
```

#### 4.4.3 下行事件

服务端下行事件统一包含 `type`、`channel`、`timestamp`，并回传 `requestId`、`conversationId`、`agent` 等追踪字段。

```json
{
  "type": "delta",
  "channel": "openclaw",
  "timestamp": 1779465600000,
  "requestId": "req-001",
  "conversationId": "conv-001",
  "content": "可以先由吴用拆解任务..."
}
```

| 事件 | 说明 |
|------|------|
| `connected` | 连接建立，返回 sessionId 和 capabilities |
| `started` | chat 流开始 |
| `delta` | chat 流式内容片段 |
| `done` | chat 流正常结束 |
| `stopped` | stop 请求已处理，包含 `stoppedCount` 表示本次实际取消的流数量 |
| `pong` | ping 响应 |
| `agent_registered` | WebSocket Agent 注册成功，返回 agentId、token 和 status |
| `agent_status_updated` | WebSocket Agent 状态上报成功 |
| `task_assigned` | WebSocket 任务分配成功 |
| `task_reported` | WebSocket 任务状态上报成功 |
| `error` | 请求参数、JSON 格式或运行时错误 |
| `agent_status` | Agent 状态变化 |
| `task_event` | 悬赏任务事件 |

---

## 5. 协作任务管理（复用 Task 模块）

### 5.1 功能说明

使用现有 Task 模块的 JobHandler 机制进行 Agent 任务管理。

### 5.2 复用现有组件

| 组件 | 文件 | 说明 |
|------|------|------|
| JobHandler | task 模块 | 定时任务处理器 |
| MemoryJobHandler | `cn.jia.chat.job.MemoryJobHandler` | 已实现的记忆同步任务 |

### 5.3 Agent 任务执行

Agent 任务通过 TaskTool 编排执行：

```java
// ChatClientConfig 中的 TaskTool 配置
var taskTool = TaskTool.builder()
        .subagentTypes(ClaudeSubagentType.builder()
                .chatClientBuilder("default", chatClientBuilder.clone())
                .skillsDirectories(List.of(skillsRootDirectories))
                .build())
        .build();
```

### 5.4 悬赏任务模型

悬赏榜是前端展示 Agent 任务的核心视图。后端优先复用 Task 模块，但需要为 Agent 任务建立统一 meta，避免前端通过 `jiacn=agent:{agentId}` 这类临时约定长期适配。

```java
@Data
public class AgentTaskMeta {
    private String taskType;            // 固定 agent
    private String rewardStatus;        // open/assigned/running/completed/failed
    private String assignedAgentId;     // 执行 Agent
    private List<String> requiredAbilities;
    private Integer reward;             // 可选，积分或权重
    private Long assignedAt;
    private Long startedAt;
    private Long completedAt;
    private String failureReason;
}
```

状态映射：

| 悬赏榜状态 | 含义 | 后端判定 |
|------|------|------|
| `open` | 待领取 | `assignedAgentId` 为空 |
| `assigned` | 已分配未开始 | 已有 `assignedAgentId`，任务未执行 |
| `running` | 执行中 | Agent 状态为 `busy` 或任务详情执行中 |
| `completed` | 已完成 | Task 完成或 Agent 上报完成 |
| `failed` | 执行失败 | Task 失败或 Agent 上报错误 |

分配约束：

- `offline`、`error` 状态 Agent 不允许分配新任务。
- `busy` 状态 Agent 默认不允许分配新任务；如需要排队，必须在请求中显式 `allowQueue=true`。
- 所需能力与 Agent `abilities` 无交集时返回 `AGENT_ABILITY_MISMATCH`。
- 任务分配、开始、完成、失败必须产生 `task_event`，并可同步写入聚义厅系统公告。

### 5.5 悬赏榜显式指派交互契约

悬赏榜不得依赖“当前已选中好汉”作为唯一指派前置条件。前端推荐人选区域必须支持对每个 Agent 直接操作。

| 操作 | 行为 | 结果 |
|------|------|------|
| `选中` | 将推荐 Agent 写入 `selectedAgent` | 当前好汉卡更新，悬赏详情保持不变 |
| `指派` | 调用 `assignTask(task, agent)` | 请求 `/agent/tasks/{taskId}/assign`，body 显式传入 `agentId` |
| `传令` | 打开传令面板并带入 Agent 与任务上下文 | 输入框可预填议事文本，metadata 带 `selectedAgentId` 和 `selectedTaskId` |

按钮文案必须包含目标 Agent 名称，例如“指派给 吴用”。如果用户使用当前好汉快捷指派，按钮文案也必须显式显示目标，例如“指派给当前好汉：吴用”，不能只显示“指派当前好汉”。

接口层保持现有后端契约：

```http
POST /agent/tasks/{taskId}/assign
```

```json
{
  "agentId": "agent-001",
  "allowQueue": false
}
```

### 5.6 前端悬赏榜查询契约

后端应提供面向悬赏榜的聚合查询，减少前端分别拉 Task、Agent、统计数据后自行拼装。

```http
POST /agent/tasks/search
```

请求体：

```json
{
  "status": "open",
  "ability": "code-review",
  "keyword": "订单",
  "pageNum": 1,
  "pageSize": 20
}
```

响应项：

```json
{
  "id": "task-001",
  "title": "分析订单模块异常",
  "status": "running",
  "requiredAbilities": ["code-review"],
  "assignedAgentId": "agent-001",
  "assignedAgentName": "吴用",
  "updatedAt": 1780071727937
}
```

---

## 6. API 设计

### 6.1 复用现有 Chat API

| 方法 | 路径 | 描述 |
|------|------|------|
| POST | `/chat/stream` | 流式聊天，返回 SSE |
| POST | `/chat/stop_stream` | 停止流传输 |
| POST | `/chat/conversation/list` | 分页查询会话列表 |
| GET | `/chat/conversation/content` | 获取会话消息内容 |
| POST | `/chat/conversation/update` | 修改会话标题 |
| DELETE | `/chat/conversation/delete` | 删除会话 |
| WS | `/ws/chat` | 普通 Chat WebSocket 流式聊天 |
| WS | `/ws/agent/channel` | Agent Channel WebSocket 事件通道 |

### 6.2 Agent 管理 API（扩展）

| 方法 | 路径 | 描述 |
|------|------|------|
| POST | `/agent/register` | Agent 运行时注册，返回 token |
| GET | `/agent/list` | 获取 Agent 列表，支持状态和能力筛选 |
| GET | `/agent/{agentId}` | 获取 Agent 详情 |
| PUT | `/agent/{agentId}/status` | Agent 状态上报或管理端修正状态 |
| GET | `/agent/{agentId}/tasks` | 获取 Agent 相关任务 |
| GET | `/agent/personas` | 获取所有 Agent 人设列表 |
| GET | `/agent/persona/{name}` | 获取指定人设详情 |
| POST | `/agent/dialogue` | 生成 Agent 水浒风格对话 |
| GET | `/agent/stats` | 获取 Agent 统计信息 |
| POST | `/agent/tasks/search` | 聚义厅悬赏榜任务聚合查询 |
| POST | `/agent/tasks/{taskId}/assign` | 分配任务给 Agent |
| POST | `/agent/tasks/{taskId}/report` | Agent 上报任务进度、完成或失败 |

### 6.3 API 响应和错误码

Agent API 使用 Jia 统一 `JsonResult` 结构；分页使用 `JsonResultPage`。前端至少需要识别以下错误码：

| 错误码 | HTTP 状态 | 说明 | 前端处理 |
|------|------|------|------|
| `AGENT_NOT_FOUND` | 404 | Agent 不存在 | 提示并刷新列表 |
| `AGENT_OFFLINE` | 409 | Agent 离线 | 禁用分配操作 |
| `AGENT_BUSY` | 409 | Agent 忙碌 | 提示正在执行任务，必要时允许排队确认 |
| `AGENT_ERROR` | 409 | Agent 异常 | 打开详情查看错误 |
| `AGENT_ABILITY_MISMATCH` | 400 | Agent 能力不满足任务要求 | 禁止分配并提示缺失能力 |
| `TOKEN_INVALID` | 401 | Agent token 无效 | 要求重新注册 |
| `TASK_NOT_FOUND` | 404 | 悬赏任务不存在 | 刷新悬赏榜 |
| `TASK_STATUS_INVALID` | 409 | 当前任务状态不允许操作 | 刷新任务详情 |

错误响应示例：

```json
{
  "code": "AGENT_OFFLINE",
  "status": 409,
  "msg": "Agent is offline",
  "data": null
}
```

---

## 7. 数据模型

### 7.1 Agent 人设模型

```java
@Data
public class AgentPersonaEntity {
    private Long id;
    private String name;              // 水浒人物名称 (如: 宋江)
    private String title;             // 称号 (如: 及时雨)
    private String avatar;            // 头像URL
    private List<String> abilities;   // 能力列表
    private String personality;        // 性格描述
    private String speakingStyle;     // 说话风格
    private String background;         // 背景故事
    private Integer power;            // 武力值
    private Integer intelligence;     // 智力值
    private Integer leadership;       // 领导力
    private Boolean active;           // 是否启用
}
```

### 7.2 Agent 运行时模型

```java
@Data
public class AgentRuntimeDTO {
    private String agentId;
    private String name;
    private String avatar;
    private String personaName;
    private List<String> abilities;
    private String status;             // online/busy/offline/error
    private String endpoint;
    private String currentTaskId;
    private String currentTaskTitle;
    private Long lastSeenAt;
    private String errorMessage;
    private AgentStatsDTO stats;
}

@Data
public class AgentStatsDTO {
    private Integer completedTaskCount;
    private Integer failedTaskCount;
    private Long averageDurationSeconds;
    private Integer power;
    private Integer intelligence;
    private Integer leadership;
}
```

### 7.3 Agent 状态事件模型

```java
@Data
public class AgentStatusEvent {
    private String type;               // agent_status
    private String agentId;
    private String status;             // online/busy/offline/error
    private TaskSummary currentTask;
    private String errorMessage;
    private Long updatedAt;
}

@Data
public class TaskSummary {
    private String id;
    private String title;
}
```

### 7.4 对话模板模型

```java
@Data
public class DialogueTemplateEntity {
    private Long id;
    private String personaName;       // 关联的人设
    private String dialogueType;      // IDLE/WORKING/COMPLETION/ERROR
    private String content;           // 台词内容
    private String triggerCondition;  // 触发条件
    private Integer priority;         // 优先级
}
```

### 7.5 复用现有数据模型

使用 `Task` 接口管理 Agent 任务，复用 jia-api-project-spec.md 中定义的 Task 模型。Agent 专属扩展字段放入 `AgentTaskMeta`，并由 `/agent/tasks/search` 聚合输出给前端。

---

## 7.6 数据库设计

### 7.6.1 ER 图

```
┌─────────────────────┐       ┌──────────────────────┐
│  agent_persona      │       │  dialogue_template   │
│  (人设表)            │       │  (对话模板表)          │
├─────────────────────┤       ├──────────────────────┤
│ id (PK)             │───┐   │ id (PK)              │
│ name                │   │   │ persona_id (FK)      │
│ title               │   └──►│ persona_name         │
│ abilities           │       │ dialogue_type        │
│ power/intelligence  │       │ content              │
│ active              │       └──────────────────────┘
└─────────────────────┘
          ▲
          │ persona_name
┌─────────────────────┐       ┌──────────────────────┐
│  agent_runtime      │       │  agent_task_meta     │
│  (运行时注册表)       │       │  (任务扩展表)          │
├─────────────────────┤       ├──────────────────────┤
│ agent_id (UK)       │◄──────│ assigned_agent_id    │
│ name/status         │       │ task_id (UK)         │
│ abilities           │       │ reward_status        │
│ current_task_id     │       │ required_abilities   │
│ last_seen_at        │       │ assigned/started_at  │
└─────────────────────┘       └──────────────────────┘
```

### 7.6.2 表结构 SQL

```sql
-- =============================================
-- 聚义厅模块数据库表结构
-- 依赖: Jia 微服务框架基础表结构
-- =============================================

-- ----------------------------
-- 1. Agent 人设表
-- ----------------------------
DROP TABLE IF EXISTS agent_persona;
CREATE TABLE agent_persona (
    id              BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    name            VARCHAR(50) NOT NULL COMMENT '水浒人物名称',
    title           VARCHAR(100) DEFAULT NULL COMMENT '称号(绰号)',
    avatar          VARCHAR(500) DEFAULT NULL COMMENT '头像URL',
    abilities       TEXT COMMENT '能力列表(JSON数组)',
    personality     VARCHAR(500) DEFAULT NULL COMMENT '性格描述',
    speaking_style  VARCHAR(500) DEFAULT NULL COMMENT '说话风格',
    background      TEXT COMMENT '背景故事',
    power           INT DEFAULT 0 COMMENT '武力值(1-100)',
    intelligence    INT DEFAULT 0 COMMENT '智力值(1-100)',
    leadership      INT DEFAULT 0 COMMENT '领导力(1-100)',
    active          TINYINT(1) DEFAULT 1 COMMENT '是否启用: 0-禁用 1-启用',
    create_time     BIGINT DEFAULT NULL COMMENT '创建时间',
    update_time     BIGINT DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name),
    KEY idx_active (active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent人设表';

-- ----------------------------
-- 2. Agent 运行时注册表
-- ----------------------------
DROP TABLE IF EXISTS agent_runtime;
CREATE TABLE agent_runtime (
    id                  BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    agent_id            VARCHAR(100) NOT NULL COMMENT 'Agent唯一标识',
    name                VARCHAR(100) NOT NULL COMMENT 'Agent显示名称',
    avatar              VARCHAR(500) DEFAULT NULL COMMENT '头像URL',
    persona_name        VARCHAR(50) DEFAULT NULL COMMENT '关联人设名称',
    abilities           TEXT COMMENT '能力列表(JSON数组)',
    endpoint            VARCHAR(500) DEFAULT NULL COMMENT 'Agent回调或WebSocket地址',
    token_hash          VARCHAR(200) DEFAULT NULL COMMENT 'Agent token摘要',
    status              VARCHAR(20) NOT NULL DEFAULT 'offline' COMMENT 'online/busy/offline/error',
    current_task_id     VARCHAR(100) DEFAULT NULL COMMENT '当前任务ID',
    current_task_title  VARCHAR(200) DEFAULT NULL COMMENT '当前任务标题',
    last_seen_at        BIGINT DEFAULT NULL COMMENT '最后心跳时间',
    error_message       VARCHAR(1000) DEFAULT NULL COMMENT '错误信息',
    create_time         BIGINT DEFAULT NULL COMMENT '创建时间',
    update_time         BIGINT DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_id (agent_id),
    KEY idx_status (status),
    KEY idx_persona_name (persona_name),
    KEY idx_last_seen_at (last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent运行时注册表';

-- ----------------------------
-- 3. 对话模板表
-- ----------------------------
DROP TABLE IF EXISTS dialogue_template;
CREATE TABLE dialogue_template (
    id                  BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    persona_id          BIGINT DEFAULT NULL COMMENT '人设ID(关联agent_persona)',
    persona_name        VARCHAR(50) NOT NULL COMMENT '人设名称',
    dialogue_type       VARCHAR(20) NOT NULL COMMENT '对话类型: IDLE空闲 WORKING工作中 COMPLETION完成 ERROR错误',
    content             VARCHAR(1000) NOT NULL COMMENT '台词内容',
    trigger_condition   VARCHAR(200) DEFAULT NULL COMMENT '触发条件',
    priority            INT DEFAULT 0 COMMENT '优先级(数值越大越优先)',
    active              TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    create_time         BIGINT DEFAULT NULL COMMENT '创建时间',
    update_time         BIGINT DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_persona (persona_id),
    KEY idx_persona_name (persona_name),
    KEY idx_dialogue_type (dialogue_type),
    CONSTRAINT fk_dialogue_persona FOREIGN KEY (persona_id) REFERENCES agent_persona(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话模板表';

-- ----------------------------
-- 4. Agent 任务扩展元数据表
-- ----------------------------
DROP TABLE IF EXISTS agent_task_meta;
CREATE TABLE agent_task_meta (
    id                  BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    task_id             VARCHAR(100) NOT NULL COMMENT '关联Task ID',
    reward_status       VARCHAR(20) NOT NULL DEFAULT 'open' COMMENT 'open/assigned/running/completed/failed',
    assigned_agent_id   VARCHAR(100) DEFAULT NULL COMMENT '分配Agent ID',
    required_abilities  TEXT COMMENT '所需能力(JSON数组)',
    reward              INT DEFAULT NULL COMMENT '奖励或权重',
    assigned_at         BIGINT DEFAULT NULL COMMENT '分配时间',
    started_at          BIGINT DEFAULT NULL COMMENT '开始时间',
    completed_at        BIGINT DEFAULT NULL COMMENT '完成时间',
    failure_reason      VARCHAR(1000) DEFAULT NULL COMMENT '失败原因',
    create_time         BIGINT DEFAULT NULL COMMENT '创建时间',
    update_time         BIGINT DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_id (task_id),
    KEY idx_reward_status (reward_status),
    KEY idx_assigned_agent_id (assigned_agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent任务扩展元数据表';

-- ----------------------------
-- 5. 初始化数据 - 经典水浒人物
-- ----------------------------
INSERT INTO agent_persona (name, title, avatar, abilities, personality, speaking_style, background, power, intelligence, leadership) VALUES
('宋江', '及时雨', '/images/agents/songjiang.png', '["领导力", "谈判", "战略规划"]', 
 '忠义双全,善于笼络人心', '自称"洒家",语气谦和但不失威严', '梁山泊寨主,以义气著称', 70, 85, 100),
('吴用', '智多星', '/images/agents/wuyong.png', '["智谋", "策划", "分析"]', 
 '足智多谋,沉着冷静', '自称"愚",说话文雅,喜用典故', '梁山军师,足智多谋', 30, 100, 75),
('李逵', '黑旋风', '/images/agents/likui.png', '["战斗", "忠诚", "执行"]', 
 '性格直爽,忠诚护主,有时鲁莽', '自称"俺",说话粗犷豪放', '宋江心腹,勇猛无畏', 95, 40, 30),
('武松', '行者', '/images/agents/wusong.png', '["战斗", "侦查", "正义"]', 
 '武艺高强,嫉恶如仇', '自称"洒家",说话简洁有力', '景阳冈打虎英雄,快意恩仇', 98, 65, 55),
('鲁智深', '花和尚', '/images/agents/luzhishen.png', '["战斗", "力气", "豪爽"]', 
 '豪放不羁,爱打抱不平', '自称"洒家",说话痛快淋漓', '倒拔垂杨柳,力大无穷', 99, 50, 45);

-- ----------------------------
-- 6. 初始化数据 - 对话模板
-- ----------------------------
INSERT INTO dialogue_template (persona_id, persona_name, dialogue_type, content, priority) VALUES
-- 宋江台词
(1, '宋江', 'IDLE', '各位兄弟,有何高见？', 10),
(1, '宋江', 'IDLE', '但凡山寨有事,洒家必亲自过问。', 10),
(1, '宋江', 'IDLE', '今日天色甚好,不如聚义厅中议事。', 5),
(1, '宋江', 'WORKING', '此事关乎山寨安危,诸位务必尽心。', 10),
(1, '宋江', 'WORKING', '洒家已有了计较,诸位听我号令。', 10),
(1, '宋江', 'COMPLETION', '好！诸位兄弟辛苦了！', 10),
(1, '宋江', 'COMPLETION', '此事办得漂亮,山寨有福了！', 5),
(1, '宋江', 'ERROR', '此事还需从长计议,不可草率。', 10),
-- 吴用台词
(2, '吴用', 'IDLE', '愚有一计,可解此围。', 10),
(2, '吴用', 'IDLE', '此事需从长计议。', 10),
(2, '吴用', 'IDLE', '军师我夜观天象,今日必有好事。', 5),
(2, '吴用', 'WORKING', '依我之计,可分三步行事。', 10),
(2, '吴用', 'WORKING', '此乃雕虫小技,诸位不必惊慌。', 10),
(2, '吴用', 'WORKING', '兵者,诡道也。吾已有破敌之策。', 8),
(2, '吴用', 'COMPLETION', '计谋已成,诸位可以放心了。', 10),
(2, '吴用', 'ERROR', '此计尚需完善,容我再思。', 10),
-- 李逵台词
(3, '李逵', 'WORKING', '哥哥,俺的铁牛来也！', 10),
(3, '李逵', 'WORKING', '哪个敢惹俺,俺一斧子砍了他！', 10),
(3, '李逵', 'WORKING', '杀去东京,夺了鸟位！', 8),
(3, '李逵', 'COMPLETION', '哈哈哈！痛快！', 10),
(3, '李逵', 'ERROR', '这鸟厮！俺非砍了他不可！', 10),
-- 武松台词
(4, '武松', 'IDLE', '景阳冈的老虎都打死了,还怕这个？', 10),
(4, '武松', 'IDLE', '洒家行不更名,坐不改姓！', 10),
(4, '武松', 'WORKING', '大哥,有事尽管吩咐！', 10),
(4, '武松', 'WORKING', '管他是谁,洒家都不怕！', 8),
(4, '武松', 'COMPLETION', '成了！洒家先干为敬！', 10),
(4, '武松', 'ERROR', '哼！这点小事算什么！', 10),
-- 鲁智深台词
(5, '鲁智深', 'IDLE', '洒家不戒酒肉,心中自得快活！', 10),
(5, '鲁智深', 'WORKING', '禅杖打开危险路,戒刀杀尽不平人。', 10),
(5, '鲁智深', 'WORKING', '谁惹洒家不高兴,洒家让他不高兴！', 10),
(5, '鲁智深', 'COMPLETION', '哈哈哈！痛快！洒家喝酒去！', 10),
(5, '鲁智深', 'ERROR', '直娘贼！洒家这禅杖可不认人！', 10);
```

### 7.6.3 字段说明

| 表名 | 字段 | 类型 | 说明 |
|------|------|------|------|
| agent_persona | abilities | TEXT | JSON数组格式,如: ["领导力","谈判"] |
| agent_persona | power | INT | 武力值 1-100 |
| agent_persona | intelligence | INT | 智力值 1-100 |
| agent_persona | leadership | INT | 领导力 1-100 |
| agent_runtime | agent_id | VARCHAR(100) | 前端和 OpenClaw 使用的 Agent 唯一标识 |
| agent_runtime | status | VARCHAR(20) | online/busy/offline/error |
| agent_runtime | abilities | TEXT | JSON数组格式 |
| agent_task_meta | reward_status | VARCHAR(20) | open/assigned/running/completed/failed |
| agent_task_meta | required_abilities | TEXT | JSON数组格式 |
| dialogue_template | dialogue_type | VARCHAR(20) | IDLE/WORKING/COMPLETION/ERROR |
| dialogue_template | priority | INT | 优先级,随机选择时权重 |

---

## 8. 技术架构

### 8.1 与现有技术栈一致

| 层级 | 技术选型 |
|------|----------|
| 框架 | Spring Boot + Spring Cloud |
| 语言 | Java 17+ |
| 构建工具 | Gradle 8.0+ |
| AI 集成 | Spring AI + spring-ai-agent-utils |
| 数据库 | MySQL 5.7+ |
| ORM | MyBatis Plus |
| 缓存 | Redis |
| 向量存储 | Elasticsearch |

### 8.2 Agent Tools 集成

| 工具 | 说明 | 依赖 |
|------|------|------|
| TaskTool | 任务分解与子代理编排 | spring-ai-agent-utils |
| SkillsTool | 技能加载与执行 | spring-ai-agent-utils |
| ClaudeSubagentType | Claude 子代理类型 | spring-ai-agent-utils |
| GrepTool | 代码内容搜索 | spring-ai-agent-utils |
| ShellTools | Shell 命令执行 | spring-ai-agent-utils |
| SmartWebFetchTool | 智能网页抓取 | spring-ai-agent-utils |
| TodoWriteTool | 待办事项管理 | spring-ai-agent-utils |

### 8.3 前端页面拆分边界

`JuyiHall.vue` 当前承担地图舞台、面板编排、数据加载、聊天流、任务指派和 toast 等职责。为了支持后续持续迭代，前端应按以下边界逐步拆分：

| 单元 | 职责 | 主要输入/输出 |
|------|------|---------------|
| `HallStage.vue` | 地图舞台、Agent 站位、偏厅候命提示、地图控制 | 输入 Agent 列表和选中态；输出 `select-agent`、`open-panel` |
| `BottomDock.vue` / `ContextDock.vue` | 常驻主操作栏和双上下文卡 | 输入当前好汉、当前任务、统计数；输出 `open-panel`、`clear-agent`、`clear-task` |
| `BountyPanel.vue` | 悬赏筛选、详情、推荐 Agent 行内操作 | 输出 `select-task`、`select-agent`、`assign-task(task, agent)`、`brief-task(task, agent)` |
| `ChatPanel.vue` | 传令上下文、快捷模板、消息展示、输入 | 输出 `mention-agent`、`apply-template`、`send-message` |
| `useHallData.js` | Agent/任务加载、筛选、状态归一化 | 暴露 `agents`、`tasks`、`loadAgents`、`loadTasks`、筛选计算 |
| `useHallConversation.js` | 会话列表、消息加载、流式发送、轮询/事件流 | 暴露 `messages`、`sendHallMessage`、`loadHallMessages`、连接状态 |

拆分约束：

- 第一阶段允许先在 `JuyiHall.vue` 内完成行为闭环，但新增方法签名应提前使用 `assignTask(task, agent)`、`briefTask(task, agent)`。
- 抽 composable 时不得改变请求 payload 字段；测试应先固定行为，再做拆分。
- `HallStage.vue` 只处理视觉舞台，不直接调用 API。

---

## 9. 目录结构

### 9.1 模块目录结构

```
agent/                           # Agent 模块（新建）
├── build.gradle
├── jia-agent-api/              # API层 - Service接口定义
│   └── src/main/java/cn/jia/agent/
│       ├── service/
│       │   └── AgentService.java          # Agent服务接口
│       └── entity/
│           ├── AgentPersonaEntity.java     # Agent人设实体
│           ├── AgentRuntimeDTO.java        # 前端列表/详情DTO
│           └── AgentTaskDTO.java           # 悬赏榜任务DTO
├── jia-agent-core/             # Core层 - DTO、常量
│   └── src/main/java/cn/jia/agent/
│       ├── dto/
│       │   ├── AgentRegisterDTO.java
│       │   ├── AgentStatusDTO.java
│       │   ├── AgentTaskSearchDTO.java
│       │   ├── AgentTaskAssignDTO.java
│       │   └── DialogueRequestDTO.java
│       └── constant/
│           └── AgentConstants.java
├── jia-agent-mapper/           # Mapper层
│   └── src/main/resources/
│       └── cn/jia/agent/mapper/
│           ├── AgentPersonaMapper.xml
│           ├── AgentRuntimeMapper.xml
│           ├── AgentTaskMetaMapper.xml
│           └── DialogueTemplateMapper.xml
└── jia-agent-service/          # Service层 - 业务逻辑
    └── src/main/java/cn/jia/agent/
        ├── service/
        │   └── impl/
        │       └── AgentServiceImpl.java
        ├── handler/
        │   └── AgentController.java
        └── job/
            └── AgentStatsJobHandler.java   # Agent统计定时任务
```

---

## 10. MVP 范围定义

### 10.1 必须实现（MVP）

- [x] Agent 人设实体、运行时实体、任务元数据实体和 DAO
- [x] Agent 注册、列表、详情、状态更新接口
- [x] Agent 详情统计接口，返回完成数、失败数、平均耗时和人设能力值
- [x] 悬赏榜聚合查询 `/agent/tasks/search`
- [x] 任务分配 `/agent/tasks/{taskId}/assign` 和状态上报 `/agent/tasks/{taskId}/report`
- [ ] 聚义厅聊天支持 `conversationType=juyiting` 或等价 metadata（Chat 实体/DTO 扩展待落地）
- [x] OpenClaw Channel 支持 chat、stop、ping、agent.register、agent.status、task.assign、task.report、agent_status、task_event
- [x] 水浒风格台词系统，作为非阻塞增强字段返回
- [ ] 与现有 ChatClientConfig、Task 模块和 LongTermMemoryAdvisor 集成（当前已接入 AgentTaskMeta 与事件桥，Task/Chat 深度联动待补）
- [ ] `cyf-web-kit` 聚义厅页面完成主操作栏、双上下文锁定、显式任务指派和传令模板
- [ ] 聚义厅页面在超过 12 个 Agent 时展示偏厅候命数量，并通过名册筛选查看全量 Agent

### 10.2 暂不实现（后续版本）

- [ ] 独立前端管理后台页面（聚义厅体验页由 `cyf-web-kit` 实现）
- [ ] 自动抢单和复杂派单策略
- [ ] 更复杂的 Agent 自由活动动画和气泡闲聊
- [ ] 108 将完整配置
- [ ] 复杂 Agent 协作编排系统
- [ ] 实时推送完全替代主动刷新
- [ ] Agent 详情内的完整能力评估报告

### 10.3 前后端联调验收标准

- [ ] 前端进入聚义厅后，调用 `/agent/list` 能拿到 Agent 卡片所需字段。
- [ ] Agent 状态切换为 `busy/offline/error` 后，前端卡片和详情弹窗能同步展示。
- [ ] `/agent/tasks/search` 返回的任务项足以渲染悬赏榜卡片，无需前端二次拼装 Agent 名称。
- [ ] 对 `offline/error` Agent 分配任务时，后端返回明确错误码。
- [ ] 聚义厅聊天会话可以通过 `conversationType=juyiting` 或 metadata 从普通聊天中筛选。
- [ ] OpenClaw Channel 的 `agent_status` 和 `task_event` 事件字段与本规格一致。
- [ ] 页面常驻操作栏可打开 `名册`、`悬赏`、`传令`，且当前激活入口有可见状态。
- [ ] 页面同时展示当前好汉和当前任务；清空任一上下文不会清空另一个上下文。
- [ ] 悬赏榜推荐 Agent 行内可直接“指派给 XXX”，请求体包含对应 `agentId`，无需用户先手动选中该 Agent。
- [ ] 传令面板顶部展示当前对象、当前任务和连接/同步状态；发送 metadata 包含 `selectedAgentId`、`mentionAgentIds`、`selectedTaskId`。
- [ ] `汇报状态`、`评估风险`、`接令确认` 三个快捷模板能插入带上下文的文本，且不会自动发送。
- [ ] 当 Agent 总数超过舞台展示上限时，舞台显示“另有 N 位在偏厅候命”或等价提示，名册仍可筛选全量 Agent。

### 10.4 前端协作链路实施路线

| 阶段 | 目标 | 主要工作 | 验证 |
|------|------|----------|------|
| 第 1 阶段 | 选人和指派闭环 | 启用/扩展常驻操作栏；新增当前任务摘要；悬赏榜推荐 Agent 行内 `选中/指派/传令`；`assignTask(task, agent)` 显式传参 | 源码契约测试 + `npm run test`；浏览器手工验证指派请求体 |
| 第 2 阶段 | 传令有上下文 | ChatPanel 顶部展示当前对象、当前任务、连接状态；新增 3 个快捷模板；系统/Agent/用户消息分层更清晰 | 组件/源码契约测试；发送请求 metadata 验证 |
| 第 3 阶段 | 页面职责拆分 | 抽 `HallStage.vue`、`ContextDock.vue`、`useHallData.js`、`useHallConversation.js`；保留原 API payload 和事件名 | 回归第 1、2 阶段测试；`npm run build` |
| 第 4 阶段 | 后续增强 | 偏厅候命聚合、实时推送增强、Agent 能力报告入口 | 独立立项，不阻塞本轮体验闭环 |

推荐优先落地第 1、2 阶段；第 3 阶段应在行为测试固定后执行，避免重构时改变用户可见行为。

### 10.5 本轮验证状态

- [x] `:agent:jia-agent-service:test` 覆盖注册、离线分配拒绝、任务上报触发 Agent 状态和任务事件。
- [x] `:agent:*:compileJava`、`:chat:jia-chat-service:compileJava`、`:chat:jia-chat-starter:compileJava` 通过。
- [x] `jia_dev` 已执行 `agent/jia-agent-mapper/src/main/resources/db/schema.sql`，并验证应用账号可读写。
- [x] HTTP 联调通过：Agent 注册、列表、任务分配、任务上报、统计、离线状态。
- [x] OpenClaw Channel 联调通过：连接、ping、Agent 注册、任务分配、任务上报、事件回推、chat 流式响应。
- [x] Chat 记忆表已兼容 OpenClaw 字符串会话 ID：`chat_message.conversation_id` 使用 `varchar(100)`。

---

## 11. 水浒人物台词示例

### 11.1 宋江（寨主）

```java
private static final List<String> SONGJIANG_IDLE = Arrays.asList(
    "各位兄弟，有何高见？",
    "但凡山寨有事，洒家必亲自过问。",
    "今日天色甚好，不如聚义厅中议事。"
);

private static final List<String> SONGJIANG_WORKING = Arrays.asList(
    "此事关乎山寨安危，诸位务必尽心。",
    "洒家已有了计较，诸位听我号令。",
    "此事还需从长计议，不可草率。"
);
```

### 11.2 吴用（军师）

```java
private static final List<String> WUYONG_IDLE = Arrays.asList(
    "愚有一计，可解此围。",
    "此事需从长计议。",
    "军师我夜观天象，今日必有好事。"
);

private static final List<String> WUYONG_WORKING = Arrays.asList(
    "依我之计，可分三步行事。",
    "此乃雕虫小技，诸位不必惊慌。",
    "兵者，诡道也。吾已有破敌之策。"
);
```

### 11.3 李逵

```java
private static final List<String> LIKEUI_WORKING = Arrays.asList(
    "哥哥，俺的铁牛来也！",
    "哪个敢惹俺，俺一斧子砍了他！",
    "杀去东京，夺了鸟位！"
);
```

### 11.4 武松

```java
private static final List<String> WUSONG_WORKING = Arrays.asList(
    "景阳冈的老虎都打死了，还怕这个？",
    "洒家行不更名，坐不改姓！",
    "大哥，有事尽管吩咐！"
);
```

### 11.5 鲁智深

```java
private static final List<String> LUZHISHEN_WORKING = Arrays.asList(
    "洒家不戒酒肉，心中自得快活！",
    "禅杖打开危险路，戒刀杀尽不平人。",
    "谁惹洒家不高兴，洒家让他不高兴！"
);
```

---

## 12. 能力评估功能

### 12.1 功能概述

能力评估模块用于评估和比较不同 Agent 的综合能力，帮助用户了解各 Agent 的擅长领域和综合实力。通过标准化的评估维度和量化指标，为 Agent 任务分配提供参考依据。

### 12.2 评估维度

| 维度 | 说明 | 评分范围 | 评估方式 |
|------|------|----------|----------|
| 智力 | 策略规划、分析决策能力 | 1-100 | 标准化测试 |
| 武力 | 战斗执行、力量表现 | 1-100 | 标准化测试 |
| 领导力 | 协调指挥、团队管理 | 1-100 | 标准化测试 |
| 忠诚度 | 任务完成度、可靠性 | 1-100 | 历史数据分析 |
| 效率 | 任务执行速度和资源利用 | 1-100 | 时间度量 |
| 创意 | 问题解决的创新性 | 1-100 | AI评估 |

### 12.3 数据模型

```java
@Data
public class AbilityEvaluationEntity {
    private Long id;
    private String agentName;              // Agent名称
    private String evaluationType;        // 评估类型: MANUAL手动/AUTO自动/COMPARISON对比
    private Integer intelligence;          // 智力得分
    private Integer power;                 // 武力得分
    private Integer leadership;            // 领导力得分
    private Integer loyalty;               // 忠诚度得分
    private Integer efficiency;            // 效率得分
    private Integer creativity;            // 创意得分
    private Integer totalScore;            // 总分
    private String evaluationContent;       // 评估内容(JSON)
    private String evaluator;              // 评估人/评估源
    private String taskContext;            // 任务上下文
    private Long evaluationTime;           // 评估时间
    private String remarks;                // 备注
}

@Data
public class AbilityComparisonEntity {
    private Long id;
    private String comparisonId;           // 对比ID(关联多个Agent)
    private String agentName;              // Agent名称
    private Integer intelligence;         // 智力得分
    private Integer power;                // 武力得分
    private Integer leadership;            // 领导力得分
    private Integer loyalty;               // 忠诚度得分
    private Integer efficiency;            // 效率得分
    private Integer creativity;            // 创意得分
    private Integer totalScore;            // 总分
    private Integer rank;                  // 排名
    private Long comparisonTime;          // 对比时间
}
```

### 12.4 能力评估API

| 方法 | 路径 | 描述 |
|------|------|------|
| POST | `/agent/evaluate` | 对单个Agent进行能力评估 |
| POST | `/agent/compare` | 对多个Agent进行能力对比 |
| GET | `/agent/evaluation/{agentName}` | 获取Agent的评估历史 |
| GET | `/agent/evaluation/latest/{agentName}` | 获取Agent最新评估结果 |
| GET | `/agent/evaluation/stats` | 获取所有Agent的评估统计 |
| DELETE | `/agent/evaluation/{id}` | 删除评估记录 |

### 12.5 评估请求/响应模型

```java
@Data
public class EvaluationRequest {
    private String agentName;              // Agent名称(可选,为空则评估所有)
    private String evaluationType;         // MANUAL/AUTO/COMPARISON
    private List<String> dimensions;      // 评估维度(为空则全部)
    private String taskContext;           // 任务上下文(用于场景化评估)
    private Integer sampleSize;           // 采样数量(用于自动评估)
}

@Data
public class EvaluationResponse {
    private String agentName;
    private Map<String, Integer> scores;  // 各维度得分
    private Integer totalScore;           // 总分
    private String level;                 // 评级: S/A/B/C/D
    private List<String> strengths;       // 优势领域
    private List<String> weaknesses;      // 弱势领域
    private String summary;               // 评估总结
}

@Data
public class ComparisonResponse {
    private List<EvaluationResponse> results;  // 各Agent评估结果
    private Map<String, Map<String, Integer>> radarData;  // 雷达图数据
    private String bestForTask;           // 最适合当前任务的Agent
    private Long comparisonTime;          // 对比时间
}
```

### 12.6 评估流程

```
┌─────────────────────────────────────────────────────────────────────┐
│                        能力评估流程                                    │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌──────────┐    ┌──────────────┐    ┌─────────────┐    ┌──────────┐ │
│  │ 发起评估  │───►│ 获取Agent配置 │───►│ 执行能力测试 │───►│ 计算得分 │ │
│  └──────────┘    └──────────────┘    └─────────────┘    └──────────┘ │
│                                                            │        │
│                                                            ▼        │
│  ┌──────────┐    ┌──────────────┐    ┌─────────────┐    ┌──────────┐ │
│  │ 返回结果  │◄───│ 生成评估报告  │◄───│ 存储评估记录 │◄───│ 汇总统计 │ │
│  └──────────┘    └──────────────┘    └─────────────┘    └──────────┘ │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 12.7 评级标准

| 评级 | 总分范围 | 说明 |
|------|----------|------|
| S | 90-100 | 卓越，各方面表现优秀 |
| A | 80-89 | 优秀，综合能力强 |
| B | 70-79 | 良好，有明显优势领域 |
| C | 60-69 | 一般，基本满足需求 |
| D | <60 | 较差，需要提升 |

### 12.8 数据库扩展

#### 12.8.1 ER图扩展

```
┌─────────────────────────┐       ┌──────────────────────────┐
│     agent_persona        │       │  ability_evaluation       │
│     (人设表)             │       │  (能力评估表)             │
├─────────────────────────┤       ├──────────────────────────┤
│ id (PK)                 │       │ id (PK)                  │
│ name                    │◄──────│ agent_name                │
│ title                   │       │ evaluation_type          │
│ power                   │       │ intelligence             │
│ intelligence            │       │ power                    │
│ leadership              │       │ leadership                │
│ ...                     │       │ loyalty                  │
└─────────────────────────┘       │ efficiency               │
                                  │ creativity               │
                                  │ total_score              │
                                  │ evaluation_content       │
                                  │ evaluator                │
                                  │ evaluation_time          │
                                  └──────────────────────────┘
```

#### 12.8.2 表结构SQL

```sql
-- ----------------------------
-- 5. 能力评估表
-- ----------------------------
DROP TABLE IF EXISTS ability_evaluation;
CREATE TABLE ability_evaluation (
    id                  BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    agent_name          VARCHAR(50) NOT NULL COMMENT 'Agent名称',
    evaluation_type     VARCHAR(20) NOT NULL COMMENT '评估类型: MANUAL手动/AUTO自动/COMPARISON对比',
    intelligence        INT DEFAULT 0 COMMENT '智力得分(1-100)',
    power               INT DEFAULT 0 COMMENT '武力得分(1-100)',
    leadership          INT DEFAULT 0 COMMENT '领导力得分(1-100)',
    loyalty             INT DEFAULT 0 COMMENT '忠诚度得分(1-100)',
    efficiency          INT DEFAULT 0 COMMENT '效率得分(1-100)',
    creativity          INT DEFAULT 0 COMMENT '创意得分(1-100)',
    total_score         INT DEFAULT 0 COMMENT '总分',
    evaluation_content  TEXT COMMENT '评估内容(JSON格式详细评估)',
    evaluator           VARCHAR(100) DEFAULT NULL COMMENT '评估人/评估源',
    task_context        VARCHAR(500) DEFAULT NULL COMMENT '任务上下文',
    evaluation_time     BIGINT DEFAULT NULL COMMENT '评估时间',
    remarks             VARCHAR(500) DEFAULT NULL COMMENT '备注',
    create_time         BIGINT DEFAULT NULL COMMENT '创建时间',
    update_time         BIGINT DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_agent_name (agent_name),
    KEY idx_evaluation_type (evaluation_type),
    KEY idx_evaluation_time (evaluation_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能力评估表';

-- ----------------------------
-- 6. 能力对比表
-- ----------------------------
DROP TABLE IF EXISTS ability_comparison;
CREATE TABLE ability_comparison (
    id                  BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    comparison_id       VARCHAR(50) NOT NULL COMMENT '对比ID(关联多个Agent)',
    agent_name          VARCHAR(50) NOT NULL COMMENT 'Agent名称',
    intelligence        INT DEFAULT 0 COMMENT '智力得分(1-100)',
    power               INT DEFAULT 0 COMMENT '武力得分(1-100)',
    leadership          INT DEFAULT 0 COMMENT '领导力得分(1-100)',
    loyalty             INT DEFAULT 0 COMMENT '忠诚度得分(1-100)',
    efficiency          INT DEFAULT 0 COMMENT '效率得分(1-100)',
    creativity          INT DEFAULT 0 COMMENT '创意得分(1-100)',
    total_score         INT DEFAULT 0 COMMENT '总分',
    rank                INT DEFAULT 0 COMMENT '排名',
    comparison_time     BIGINT DEFAULT NULL COMMENT '对比时间',
    create_time         BIGINT DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_comparison_id (comparison_id),
    KEY idx_agent_name (agent_name),
    KEY idx_comparison_time (comparison_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能力对比表';
```

### 12.9 与现有模块的集成

### 12.9.1 Chat 模块集成

```java
// 在 ChatClientConfig 中集成 Agent 人设
@Configuration
public class ChatClientConfig {
    @Value("${chat.agent.skills.dirs:}")
    private String[] skillsRootDirectories;
    
    @Value("${jia.agent.personas:}")
    private String[] agentPersonas;
    
    @Bean
    public ChatClient chatClient(...) {
        // 现有的 Agent Tools 配置...
        
        // 添加人设感知
        return chatClientBuilder
                // ... 现有配置
                .defaultAdvisors(new PersonaAwareAdvisor(agentPersonas))
                .build();
    }
}
```

### 12.9.2 记忆共享配置

Agent 通过 LongTermMemoryAdvisor 实现跨会话记忆共享：

```java
LongTermMemoryAdvisor longTermMemoryAdvisor = LongTermMemoryAdvisor.builder(memoryRepository)
        .memoryTopK(2)
        .similarityThreshold(0.75)
        .build();
```

---

## 13. 配置项

```properties
# Agent 人设配置
jia.agent.personas=宋江,吴用,李逵,武松,鲁智深

# 人设详细配置
jia.agent.persona.宋江.name=宋江
jia.agent.persona.宋江.title=及时雨
jia.agent.persona.宋江.abilities=领导力,谈判,战略规划

jia.agent.persona.吴用.name=吴用
jia.agent.persona.吴用.title=智多星
jia.agent.persona.吴用.abilities=智谋,策划,分析

# Agent 统计
jia.agent.stats.enabled=true
jia.agent.stats.cron=0 0 * * * ?

# Chat WebSocket
jia.chat.service.websocket.enable=true
jia.chat.service.websocket.allowed-origin-patterns=*

# Agent 状态
jia.agent.status.heartbeat-timeout-seconds=60
jia.agent.status.poll-default-page-size=50

# 悬赏榜
jia.agent.task.allow-busy-queue=false
jia.agent.task.default-reward=0
```

---

## 14. 变更记录

| 版本 | 日期 | 修改内容 | 作者 |
|------|------|----------|------|
| v0.1 | 2025年 | 初始版本 | - |
| v0.2 | 2025-05-04 | 优化：与 app-spec.md 协调，明确 MVP 范围，复用现有组件 | AI |
| v0.3 | 2026-05-06 | 重构：调整为后端服务模块，与 jia-api-project-spec.md 对齐，整合 Chat 模块 | AI |
| v0.4 | 2026-05-10 | 新增：能力评估功能，支持评估和比较不同Agent的综合能力 | AI |
| v0.5 | 2026-05-23 | 新增：OpenClaw Channel WebSocket 服务端入口与事件协议 | AI |
| v0.6 | 2026-05-30 | 对齐 cyf-web-kit 前端规格，补充 Agent 运行时、悬赏榜、状态事件、API 错误码和联调验收标准 | AI |
| v0.7 | 2026-05-30 | 实施：新增 agent 模块、Agent API、DAO/SQL、任务元数据、OpenClaw 事件桥和服务级验证；标注剩余 Chat/Task 深度联动缺口 | AI |
| v0.8 | 2026-05-30 | 优化：OpenClaw Channel 增加 Agent 注册、状态上报、任务分配和任务上报；完成 jia_dev HTTP/WebSocket 联调验证 | AI |
| v0.9 | 2026-06-07 | 评审聚义厅前端协作链路建议，新增主操作栏、双上下文锁定、显式指派、传令模板、Agent 溢出展示、页面拆分和实施路线规格 | AI |

---

*文档结束*
