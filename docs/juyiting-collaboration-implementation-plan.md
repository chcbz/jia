# 聚义厅协作链路功能实施方案

> 版本：v1.0  
> 日期：2026-06-07  
> 适用团队：产品、前端研发、后端研发、测试  
> 依据：`api/openspec/changes/juyiting/specs/juyiting/spec.md` v0.9、`web/jia-web-kit` 聚义厅前端实现

---

## 1. 目标

聚义厅当前已经具备较强的水浒世界观表达，但高频协作链路存在入口分散、选中态隐藏、任务指派不闭环、传令上下文不足等问题。本轮目标是把聚义厅从“可看、可互动”优化为“可高频调度、可闭环协作”的 Agent 协作工作台。

核心主链路固定为：

```text
选好汉 -> 看悬赏 -> 发传令 -> 指派/回报
```

本方案目标：

- 让用户始终看见当前好汉、当前悬赏和主要操作入口。
- 让悬赏榜推荐 Agent 可直接执行 `选中`、`指派`、`传令`。
- 让传令消息自动携带当前好汉和当前任务上下文。
- 让大厅舞台在 Agent 超过展示上限时保留全局感知。
- 在行为稳定后拆分 `JuyiHall.vue`，降低后续迭代风险。

## 2. 功能清单

### 2.1 已落地能力

| 功能 | 说明 | 关键文件 |
|------|------|----------|
| 常驻操作栏 | `BottomDock` 作为 `名册`、`悬赏`、`传令` 主入口，展示在线好汉数、悬赏数、当前好汉、当前悬赏 | `web/jia-web-kit/src/components/juyiting/BottomDock.vue`、`web/jia-web-kit/src/components/world/JuyiHall.vue` |
| 双上下文锁定 | 页面维护 `selectedAgent` 和 `selectedTask` 两个显式上下文，可独立清空 | `JuyiHall.vue`、`BottomDock.vue` |
| 显式任务指派 | `assignTask(task, agent)` 显式传入目标 Agent，请求体包含 `agentId` | `JuyiHall.vue`、`BountyPanel.vue` |
| 推荐 Agent 行内操作 | 悬赏详情中的推荐好汉支持 `选中`、`指派给 XXX`、`传令` | `BountyPanel.vue` |
| 传令上下文 | ChatPanel 顶部展示当前对话对象、当前悬赏、连接状态 | `ChatPanel.vue`、`JuyiHall.vue` |
| 快捷传令模板 | 支持 `汇报状态`、`评估风险`、`接令确认` 三个模板，只填充输入框，不自动发送 | `ChatPanel.vue`、`JuyiHall.vue` |
| 消息 metadata | 发传令时保留 `selectedAgentId`、`mentionAgentIds`、`selectedTaskId` | `JuyiHall.vue` |
| Agent 溢出提示 | 舞台仍最多展示 12 位 Agent，超出时显示“另有 N 位在偏厅候命”，点击进入名册 | `JuyiHall.vue` |
| 契约测试 | 新增聚义厅协作链路源码契约测试 | `web/jia-web-kit/tests/juyiting-collaboration-flow.test.js` |

### 2.2 待开发增强

| 功能 | 说明 | 优先级 |
|------|------|--------|
| 页面职责拆分 | 抽出 `HallStage.vue`、`useHallData.js`、`useHallConversation.js`，降低 `JuyiHall.vue` 复杂度 | P1 |
| 组件行为测试增强 | 在源码契约测试之外补充组件渲染/交互测试，覆盖按钮点击和模板填充 | P1 |
| 实时事件增强 | 用 WebSocket/SSE 增强 Agent 状态、任务事件、聊天回显，减少主动刷新依赖 | P2 |
| Agent 能力报告入口 | 在 Agent 详情中展示能力评估摘要和任务适配说明 | P2 |
| 移动端体验复核 | 针对底部 Dock、悬赏详情和聊天面板做移动端视觉回归 | P2 |

## 3. 具体实施方案

### 3.1 前端主操作与上下文

实现方案：

- 在 `JuyiHall.vue` 的 `.quick-bar` 内挂载 `BottomDock`。
- `BottomDock` 接收 `activePanel`、`agentsTotal`、`tasksTotal`、`selectedAgent`、`selectedTask`、`agentLabel`。
- `BottomDock` 输出 `open-panel`、`clear-agent`、`clear-task`。
- 当前好汉和当前悬赏允许分别清空，不互相影响。

交互规则：

- 未选好汉时显示“未选好汉”，点击进入名册。
- 未选悬赏时显示“未选悬赏”，点击进入悬赏榜。
- 已选中上下文时点击上下文卡清空对应上下文。
- `名册`、`悬赏`、`传令` 始终作为主入口保留，地图房间和热点作为辅助入口。

### 3.2 悬赏榜显式指派

实现方案：

- `canAssign(task, agent = selectedAgent.value)` 同时支持当前好汉和推荐好汉。
- `assignTask(task, agent = selectedAgent.value)` 使用 `targetAgent.agentId` 调用 `/agent/tasks/{taskId}/assign`。
- 指派成功后同步更新任务状态、任务承接人、目标 Agent 状态、当前好汉和当前任务。
- 推荐 Agent 行内提供三个动作：
  - `选中`：只更新 `selectedAgent`。
  - `指派给 XXX`：直接调用 `assignTask(selectedTask, agent)`。
  - `传令`：调用 `briefSelectedTask(selectedTask, agent)` 并打开传令面板。

接口契约：

```http
POST /agent/tasks/{taskId}/assign
```

```json
{
  "agentId": "agent-001"
}
```

后端沿用现有接口，不需要新增 API。

### 3.3 传令面板上下文与模板

实现方案：

- `ChatPanel` 新增 `selectedTask` 和 `connectionStatus` props。
- 顶部展示：
  - `对话对象：{targetText}`
  - `当前悬赏：{selectedTask.title || '未选悬赏'}`
  - `连接状态：{connectionStatus}`
- 新增 `commandTemplates`：
  - `汇报状态`
  - `评估风险`
  - `接令确认`
- 点击模板时向父组件触发 `apply-template`，由 `JuyiHall.vue` 写入 `draft`。

模板内容：

```text
请汇报「{taskTitle}」当前进展、阻塞点和下一步。
请评估「{taskTitle}」的主要风险、依赖和可回滚方案。
请确认是否接令「{taskTitle}」，并说明预计完成方式。
```

metadata 要求：

```js
metadata: {
  scene: 'juyiting',
  selectedAgentId: selectedAgent.value?.agentId,
  mentionAgentIds: selectedAgent.value?.agentId ? [selectedAgent.value.agentId] : [],
  selectedTaskId: selectedTask.value?.id
}
```

### 3.4 大厅 Agent 溢出展示

实现方案：

- `visibleAgents = filteredAgents.slice(0, 12)` 保持舞台可读性。
- `hiddenAgentCount = Math.max(filteredAgents.length - visibleAgents.length, 0)`。
- 当 `hiddenAgentCount > 0` 时展示“另有 N 位在偏厅候命”。
- 点击提示打开名册，名册继续接收全量 `agents` 和 `filteredAgents`。

### 3.5 后续拆分边界

行为稳定后按以下边界拆分：

| 单元 | 职责 | 约束 |
|------|------|------|
| `HallStage.vue` | 地图舞台、Agent 站位、偏厅提示、地图拖拽/复位 | 不直接调用 API |
| `useHallData.js` | Agent/任务加载、筛选、推荐 Agent、状态归一化 | 不改变现有接口 payload |
| `useHallConversation.js` | 会话加载、消息归一化、流式发送、轮询/事件流 | 保持 metadata 字段稳定 |

## 4. 实施步骤

### 阶段一：协作链路闭环

1. 新增 `juyiting-collaboration-flow.test.js`，锁定 Dock、显式指派、聊天模板、metadata、溢出提示契约。
2. 扩展并挂载 `BottomDock`，展示主操作入口和双上下文。
3. 修改 `BountyPanel` 推荐 Agent 行内操作。
4. 修改 `JuyiHall.vue` 的 `canAssign`、`assignTask`、`briefSelectedTask`、`applyCommandTemplate`。
5. 增加 `hiddenAgentCount` 和舞台溢出提示。
6. 运行测试和构建，确认行为稳定。

当前状态：已完成。

### 阶段二：传令上下文增强

1. `ChatPanel` 新增当前悬赏和连接状态展示。
2. `ChatPanel` 新增三个快捷模板按钮。
3. `JuyiHall.vue` 负责根据当前任务生成模板内容。
4. 确认发送消息 metadata 保持上下文字段。

当前状态：已完成。

### 阶段三：结构拆分

1. 先补充组件级交互测试，覆盖推荐 Agent 三个按钮、Dock 清空上下文、模板填充。
2. 抽 `HallStage.vue`，迁移舞台模板和样式。
3. 抽 `useHallData.js`，迁移数据加载、筛选、推荐计算。
4. 抽 `useHallConversation.js`，迁移聊天流、消息加载、事件处理。
5. 每完成一个拆分单元运行 `npm.cmd run test` 和 `npm.cmd run build`。

当前状态：待开发。

### 阶段四：后续增强

1. 补实时推送联调，减少手动刷新。
2. 在 Agent 详情中展示能力评估摘要。
3. 完成移动端视觉回归。
4. 根据真实使用反馈优化模板和推荐排序。

当前状态：待排期。

## 5. 验证步骤

### 5.1 自动化验证

在 `web/jia-web-kit` 执行：

```powershell
npm.cmd run test
npm.cmd run build
```

通过标准：

- Mocha 测试全部通过。
- Vite production build 成功。
- `juyiting-collaboration-flow.test.js` 至少覆盖以下断言：
  - 页面挂载 `<BottomDock`。
  - `BottomDock` 支持 `agentsTotal`、`tasksTotal`、`selectedAgent`、`selectedTask`。
  - `BountyPanel` 发出 `$emit('assign-task', selectedTask, agent)`。
  - `JuyiHall.vue` 使用 `assignTask(task, agent)` 和 `targetAgent.agentId`。
  - `ChatPanel` 包含 `commandTemplates`、`汇报状态`、`评估风险`、`接令确认`。
  - 发送 metadata 包含 `selectedAgentId`、`mentionAgentIds`、`selectedTaskId`。
  - 页面包含 `hiddenAgentCount`。

### 5.2 文档验证

在 `api` 执行：

```powershell
rg -n "v0\\.9|前端协作链路优化|聚义厅传令面板交互契约|悬赏榜显式指派交互契约|前端协作链路实施路线" openspec\changes\juyiting\specs\juyiting\spec.md
rg -n "待补充|待确认|未决项" docs\juyiting-collaboration-implementation-plan.md openspec\changes\juyiting\specs\juyiting\spec.md
```

通过标准：

- 能检索到 v0.9 关键章节。
- 方案文档和 spec 不包含未处理的补充项、确认项或未决项。

### 5.3 手工验证

进入聚义厅页面后验证：

1. 底部常驻栏可打开 `名册`、`悬赏`、`传令`。
2. 选择一个 Agent 后，当前好汉卡更新。
3. 选择一个悬赏后，当前悬赏卡更新。
4. 在悬赏详情中点击推荐 Agent 的 `指派给 XXX`，请求体包含该 Agent 的 `agentId`。
5. 点击推荐 Agent 的 `传令`，打开传令面板且输入框带任务与 Agent 上下文。
6. 点击 `汇报状态`、`评估风险`、`接令确认`，只填充输入框，不自动发送。
7. Agent 数量超过 12 时，舞台展示“另有 N 位在偏厅候命”，点击后打开名册。

## 6. 验收条件

### 6.1 产品验收

- 用户能通过一个常驻操作栏完成名册、悬赏、传令切换。
- 用户始终能看见当前好汉和当前悬赏。
- 用户在悬赏榜推荐人选区域可直接指派目标 Agent。
- 用户发传令前能看见对话对象、当前悬赏和连接状态。
- 快捷模板不会绕过用户确认直接发送。
- 大厅只展示部分 Agent 时仍能提示剩余候命数量。

### 6.2 技术验收

- `/agent/tasks/{taskId}/assign` 请求体显式包含目标 `agentId`。
- `sendHallMessage` metadata 保持 `selectedAgentId`、`mentionAgentIds`、`selectedTaskId`。
- `offline`、`error`、`busy` 等不可指派状态仍由 `canAssign(task, agent)` 阻止。
- 名册面板仍接收全量 Agent，不受舞台 12 人展示上限影响。
- 自动化测试和生产构建通过。
- 后续拆分不得改变用户可见行为和接口 payload。

### 6.3 交付验收

- 规格文档已更新到 v0.9。
- 前端实现已提交并推送到 `web/jia-web-kit` 的 `origin/develop`。
- 本实施方案已提供给研发团队，路径为 `api/docs/juyiting-collaboration-implementation-plan.md`。

## 7. 当前仓库状态记录

- API spec 当前依据：`api/openspec/changes/juyiting/specs/juyiting/spec.md` v0.9。
- 前端实现提交：`web/jia-web-kit` commit `4a0c2f9 feat: improve juyiting collaboration flow`。
- 自动化验证记录：
  - `npm.cmd run test`：21 passing。
  - `npm.cmd run build`：通过。
- 已知未纳入项：
  - `web/jia-web-kit/src/components.d.ts` 曾显示无实际 diff 的 modified 状态，不属于本方案功能改动。
  - 浏览器手工验证需在有可用本地服务和浏览器环境时执行。
