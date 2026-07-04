# Agent 多Agent协作模块规范

## 模块概述

Agent 多Agent协作模块提供 AI Agent 的注册、发现、能力评估、任务分配与协作管理功能，支持多 Agent 协同完成复杂任务。

- **子模块**: api, core, mapper, service
- **包名**: cn.jia.agent
- **版本**: 1.1.3-SNAPSHOT

## 核心功能

### 1. Agent 注册与管理

| 功能 | 说明 |
|------|------|
| Agent 注册 | 注册新的 AI Agent 到管理平台 |
| Agent 列表 | 分页查询 Agent 列表（支持按状态、能力过滤） |
| Agent 映射 | 按能力分组列出所有 Agent |
| Agent 详情 | 获取单个 Agent 详细信息 |
| 状态更新 | 更新 Agent 在线/离线等状态 |
| 能力列表 | 列出所有已注册的 Agent 能力 |
| 统计信息 | 获取 Agent 任务完成统计 |

### 2. 人设 (Persona) 管理

| 功能 | 说明 |
|------|------|
| 人设目录 | 列出所有可用的人设模板 |
| 人设绑定 | 将 Agent 绑定到指定人设 |
| 人设解绑 | 解除 Agent 与人设的绑定 |
| 人设列表 | 查看当前用户已绑定的人设 |
| 人设详情 | 获取已绑定人设的详细信息 |

### 3. 任务管理

| 功能 | 说明 |
|------|------|
| 任务创建 | 创建新的 Agent 任务 |
| 任务搜索 | 按条件分页搜索任务 |
| 任务详情 | 获取单个任务详情 |
| 状态统计 | 按状态统计任务数量 |
| 任务分配 | 手动分配任务给 Agent |
| 智能推荐 | 基于能力推荐合适的 Agent |
| 自动分配 | 系统自动选择最优 Agent |
| 任务报告 | 提交任务执行报告 |
| 任务笔记 | 添加/查看任务执行笔记 |
| 任务归档 | 完成任务并归档 |

### 4. 能力评估

| 功能 | 说明 |
|------|------|
| 能力评估 | 对 Agent 执行能力评估 |
| 能力对比 | 多 Agent 能力横向对比 |
| 评估历史 | 查询 Agent 历史评估记录 |
| 最新评估 | 获取 Agent 最新评估结果 |
| 评估统计 | 全局评估统计数据 |
| 删除评估 | 删除指定评估记录 |

### 5. 对话管理

| 功能 | 说明 |
|------|------|
| 对话生成 | 基于模板生成 Agent 对话 |
| 对话模板 | 管理对话模板内容 |

## 核心实体

| 实体 | 说明 |
|------|------|
| AgentRuntimeEntity | Agent 运行时信息（注册状态、地址、能力标签等） |
| AgentPersonaEntity | Agent 人设模板定义 |
| AgentPersonaBindingEntity | Agent 与人设绑定关系 |
| AgentTaskMetaEntity | 任务元数据（标题、优先级、状态等） |
| AgentTaskNoteEntity | 任务执行笔记 |
| AbilityEvaluationEntity | 能力评估记录 |
| AbilityComparisonEntity | 能力对比数据 |
| DialogueTemplateEntity | 对话模板 |

## 依赖模块

- **jia-common-core**: 基础实体和通用工具
- **jia-common-service**: 通用服务实现

## 相关文档

- [Agent 接口文档](../interfaces/jia-agent-interface-spec.md)
