# Agent 多Agent协作模块接口文档

来源模块：`agent`

本文面向前端对接，每个接口包含请求方式、地址、参数位置、必填性、请求体字段和响应结构。

## 通用响应结构

- 单对象或操作结果：`JsonResult<T>`，字段：`code`、`msg`、`status`、`data`
- 分页列表：`JsonResultPage<T>`，字段：`code`、`msg`、`status`、`pageNum`、`total`、`data`（数组）
- 成功码默认为 "E0"

---

## 1. Agent 注册与管理

### 1.1 Agent 注册
- **方式**: `POST`
- **地址**: `/agent/register`
- **请求体**: `AgentRegisterDTO`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | string | 是 | Agent 名称 |
| host | string | 否 | Agent 服务地址 |
| capabilities | string[] | 否 | 能力标签列表 |

- **响应**: `JsonResult<AgentRegisterResultDTO>`

---

### 1.2 Agent 列表
- **方式**: `GET`
- **地址**: `/agent/list`
- **Query参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| status | string | 否 | 状态过滤 |
| ability | string | 否 | 能力过滤 |
| pageNum | int | 否 | 页码（默认1） |
| pageSize | int | 否 | 每页条数（默认50） |

- **响应**: `JsonResultPage<AgentRuntimeDTO>`

---

### 1.3 Agent 按能力映射
- **方式**: `GET`
- **地址**: `/agent/map`
- **响应**: `JsonResult<Map<String, List<AgentRuntimeDTO>>>` — 按能力分组

---

### 1.4 能力列表
- **方式**: `GET`
- **地址**: `/agent/capabilities`
- **响应**: `JsonResult<List<AgentCapabilityDTO>>`

---

### 1.5 Agent 详情
- **方式**: `GET`
- **地址**: `/agent/{agentId}`
- **路径参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| agentId | string | 是 | Agent ID |

- **响应**: `JsonResult<AgentRuntimeDTO>`

---

### 1.6 更新 Agent 状态
- **方式**: `PUT`
- **地址**: `/agent/{agentId}/status`
- **路径参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| agentId | string | 是 | Agent ID |

- **请求体**: `AgentStatusDTO`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| status | string | 是 | 目标状态（online/offline） |

- **响应**: `JsonResult<AgentStatusDTO>`

---

### 1.7 Agent 任务列表
- **方式**: `GET`
- **地址**: `/agent/{agentId}/tasks`
- **路径参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| agentId | string | 是 | Agent ID |

- **响应**: `JsonResult<List<AgentTaskDTO>>`

---

### 1.8 Agent 统计
- **方式**: `GET`
- **地址**: `/agent/stats`
- **Query参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| agentId | string | 是 | Agent ID |

- **响应**: `JsonResult<AgentStatsDTO>`

---

## 2. 人设 (Persona) 管理

### 2.1 人设目录
- **方式**: `GET`
- **地址**: `/agent/personas/catalog`
- **响应**: `JsonResult<List<AgentPersonaEntity>>`

---

### 2.2 人设列表
- **方式**: `GET`
- **地址**: `/agent/personas`
- **响应**: `JsonResult<List<AgentPersonaBindingEntity>>`

---

### 2.3 人设详情
- **方式**: `GET`
- **地址**: `/agent/personas/{personaCode}`
- **路径参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| personaCode | string | 是 | 人设编码 |

- **响应**: `JsonResult<AgentPersonaBindingEntity>`

---

### 2.4 绑定人设
- **方式**: `POST`
- **地址**: `/agent/personas/{personaCode}/bind`
- **路径参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| personaCode | string | 是 | 人设编码 |

- **请求体**: `AgentPersonaBindRequestDTO`（可选）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| mode | string | 否 | 绑定模式 |

- **响应**: `JsonResult<AgentPersonaBindResultDTO>`

---

### 2.5 解绑人设
- **方式**: `DELETE`
- **地址**: `/agent/personas/{personaCode}/bind`
- **路径参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| personaCode | string | 是 | 人设编码 |

- **响应**: `JsonResult<Void>`

---

## 3. Agent 花名册查询

### 3.1 花名册查询
- **方式**: `POST`
- **地址**: `/agent/roster`
- **请求体**: `AgentRosterSearchDTO`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| status | string | 否 | 状态过滤 |
| ability | string | 否 | 能力过滤 |
| pageNum | int | 否 | 页码 |
| pageSize | int | 否 | 每页条数 |

- **响应**: `JsonResultPage<AgentRuntimeDTO>`

---

## 4. 任务管理

### 4.1 创建任务
- **方式**: `POST`
- **地址**: `/agent/tasks`
- **请求体**: `AgentTaskCreateDTO`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| title | string | 是 | 任务标题 |
| description | string | 否 | 任务描述 |
| priority | string | 否 | 优先级 |
| requiredCapability | string | 否 | 需要的 Agent 能力 |

- **响应**: `JsonResult<AgentTaskDTO>`

---

### 4.2 搜索任务
- **方式**: `POST`
- **地址**: `/agent/tasks/search`
- **请求体**: `AgentTaskSearchDTO`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| status | string | 否 | 任务状态 |
| agentId | string | 否 | 分配 Agent |
| keyword | string | 否 | 关键词搜索 |
| pageNum | int | 否 | 页码 |
| pageSize | int | 否 | 每页条数 |

- **响应**: `JsonResultPage<AgentTaskDTO>`

---

### 4.3 任务状态统计
- **方式**: `POST`
- **地址**: `/agent/tasks/status-counts`
- **请求体**: `AgentTaskSearchDTO`（同搜索条件）
- **响应**: `JsonResult<Map<String, Long>>` — 各状态计数

---

### 4.4 任务详情
- **方式**: `GET`
- **地址**: `/agent/tasks/{taskId}`
- **路径参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| taskId | string | 是 | 任务 ID |

- **响应**: `JsonResult<AgentTaskDTO>`

---

### 4.5 分配任务
- **方式**: `POST`
- **地址**: `/agent/tasks/{taskId}/assign`
- **路径参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| taskId | string | 是 | 任务 ID |

- **请求体**: `AgentTaskAssignDTO`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| agentId | string | 是 | 分配的 Agent ID |

- **响应**: `JsonResult<AgentTaskDTO>`

---

### 4.6 推荐分配
- **方式**: `POST`
- **地址**: `/agent/tasks/{taskId}/recommend`
- **路径参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| taskId | string | 是 | 任务 ID |

- **响应**: `JsonResult<List<AgentTaskRecommendationDTO>>`

---

### 4.7 自动分配
- **方式**: `POST`
- **地址**: `/agent/tasks/{taskId}/auto-assign`
- **路径参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| taskId | string | 是 | 任务 ID |

- **请求体**: `AgentTaskAssignDTO`（可选）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| agentId | string | 否 | 排除的 Agent ID |

- **响应**: `JsonResult<AgentTaskDTO>`

---

### 4.8 提交报告
- **方式**: `POST`
- **地址**: `/agent/tasks/{taskId}/report`
- **路径参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| taskId | string | 是 | 任务 ID |

- **请求体**: `AgentTaskReportDTO`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| content | string | 是 | 报告内容 |
| status | string | 否 | 任务结果状态 |

- **响应**: `JsonResult<AgentTaskDTO>`

---

### 4.9 添加笔记
- **方式**: `POST`
- **地址**: `/agent/tasks/{taskId}/notes`
- **路径参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| taskId | string | 是 | 任务 ID |

- **请求体**: `AgentTaskNoteDTO`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| content | string | 是 | 笔记内容 |

- **响应**: `JsonResult<AgentTaskNoteDTO>`

---

### 4.10 查看笔记
- **方式**: `GET`
- **地址**: `/agent/tasks/{taskId}/notes`
- **路径参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| taskId | string | 是 | 任务 ID |

- **响应**: `JsonResult<List<AgentTaskNoteDTO>>`

---

### 4.11 归档任务
- **方式**: `POST`
- **地址**: `/agent/tasks/{taskId}/archive`
- **路径参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| taskId | string | 是 | 任务 ID |

- **响应**: `JsonResult<AgentTaskDTO>`

---

## 5. 能力评估

### 5.1 能力评估
- **方式**: `POST`
- **地址**: `/agent/evaluate`
- **请求体**: `AbilityEvaluationRequestDTO`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| agentName | string | 是 | Agent 名称 |
| capability | string | 是 | 评估能力维度 |
| score | float | 是 | 评估分数 |

- **响应**: `JsonResult<AbilityEvaluationDTO>`

---

### 5.2 能力对比
- **方式**: `POST`
- **地址**: `/agent/compare`
- **请求体**: `AbilityCompareRequestDTO`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| agentNames | string[] | 是 | 对比的 Agent 名称列表 |
| capability | string | 否 | 指定能力维度 |

- **响应**: `JsonResult<List<AbilityCompareResultDTO>>`

---

### 5.3 评估历史
- **方式**: `GET`
- **地址**: `/agent/evaluation/{agentName}`
- **路径参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| agentName | string | 是 | Agent 名称 |

- **响应**: `JsonResult<List<AbilityEvaluationDTO>>`

---

### 5.4 最新评估
- **方式**: `GET`
- **地址**: `/agent/evaluation/latest/{agentName}`
- **路径参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| agentName | string | 是 | Agent 名称 |

- **响应**: `JsonResult<AbilityEvaluationDTO>`

---

### 5.5 评估统计
- **方式**: `GET`
- **地址**: `/agent/evaluation/stats`
- **响应**: `JsonResult<AgentStatsDTO>`

---

### 5.6 删除评估
- **方式**: `DELETE`
- **地址**: `/agent/evaluation/{id}`
- **路径参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | long | 是 | 评估记录 ID |

- **响应**: `JsonResult<Void>`

---

## 6. 对话生成

### 6.1 生成对话
- **方式**: `POST`
- **地址**: `/agent/dialogue`
- **请求体**: `DialogueRequestDTO`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| personaCode | string | 否 | 人设编码 |
| message | string | 是 | 用户消息 |
| context | string | 否 | 对话上下文 |

- **响应**: `JsonResult<String>` — AI 生成的回复内容

---

## 模型字段字典

### AgentRuntimeDTO

| 字段 | 类型 | 说明 |
|------|------|------|
| agentId | string | Agent 唯一标识 |
| name | string | Agent 名称 |
| status | string | 状态（online/offline/busy） |
| host | string | 服务地址 |
| capabilities | string[] | 能力标签列表 |
| createTime | long | 注册时间 |
| updateTime | long | 更新时间 |

### AgentTaskDTO

| 字段 | 类型 | 说明 |
|------|------|------|
| taskId | string | 任务 ID |
| title | string | 任务标题 |
| description | string | 任务描述 |
| status | string | 状态（pending/assigned/in_progress/completed/archived） |
| priority | string | 优先级 |
| agentId | string | 分配的 Agent ID |
| agentName | string | Agent 名称 |
| createTime | long | 创建时间 |
| updateTime | long | 更新时间 |

### AgentPersonaEntity

| 字段 | 类型 | 说明 |
|------|------|------|
| personaCode | string | 人设唯一编码 |
| name | string | 人设名称 |
| description | string | 人设描述 |
| systemPrompt | string | 系统提示词 |

### AbilityEvaluationDTO

| 字段 | 类型 | 说明 |
|------|------|------|
| id | long | 评估记录 ID |
| agentName | string | Agent 名称 |
| capability | string | 能力维度 |
| score | float | 评分 |
| comment | string | 评价备注 |
| createTime | long | 评估时间 |
