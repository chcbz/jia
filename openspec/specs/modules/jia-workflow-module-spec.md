# Workflow 工作流模块规格文档

## 模块概述

Workflow 工作流模块集成Camunda工作流引擎，提供流程定义、部署、执行和管理功能。

- **子模块**: api, core, service, starter
- **包名**: cn.jia.workflow
- **版本**: 1.1.2-SNAPSHOT

## 核心功能

### 1. 流程定义管理

| 功能 | 说明 |
|------|------|
| 定义创建 | 创建流程定义 |
| 定义查询 | 查询已部署流程 |
| 定义删除 | 删除未使用流程 |
| XML查看 | 查看流程XML |

### 2. 流程部署

| 功能 | 说明 |
|------|------|
| ZIP部署 | 批量部署流程文件 |
| 单一部署 | 部署单个流程 |
| 部署历史 | 查看部署记录 |

### 3. 任务管理

| 功能 | 说明 |
|------|------|
| 任务创建 | 创建新任务 |
| 任务分配 | 分配执行人 |
| 任务完成 | 完成任务 |
| 任务取消 | 取消任务 |
| 任务委派 | 委派他人 |

### 4. 流程实例管理

| 功能 | 说明 |
|------|------|
| 实例启动 | 启动流程实例 |
| 实例查询 | 查询运行中实例 |
| 实例取消 | 取消流程实例 |
| 实例历史 | 查看历史实例 |

### 5. 流程变量管理

| 功能 | 说明 |
|------|------|
| 变量设置 | 设置流程变量 |
| 变量查询 | 查询变量值 |
| 变量更新 | 更新变量 |

### 6. 任务批注

| 功能 | 说明 |
|------|------|
| 添加批注 | 为任务添加批注 |
| 查询批注 | 查看历史批注 |

## Service接口

### WorkflowService

```java
public interface WorkflowService {
    // 流程定义
    ProcessDefinitionEntity getProcessDefinition(String processDefinitionId);
    List<ProcessDefinitionEntity> listProcessDefinitions();
    
    // 流程部署
    DeploymentEntity deploy(String name, InputStream xml);
    
    // 流程实例
    ProcessInstanceEntity startProcess(String processDefinitionKey, Map<String, Object> variables);
    ProcessInstanceEntity getProcessInstance(String processInstanceId);
    
    // 任务管理
    TaskEntity createTask(String processInstanceId, String assignee);
    TaskEntity getTask(String taskId);
    void completeTask(String taskId, Map<String, Object> variables);
    void assignTask(String taskId, String assignee);
    List<TaskEntity> listTasks(String assignee);
    
    // 历史
    List<HistoricActivityInstanceEntity> listHistory(String processInstanceId);
}
```

## 相关模块

- **jia-common-core**: 提供基础实体
- **jia-user-core**: 用户和组织信息
- **jia-base-core**: 通知功能
