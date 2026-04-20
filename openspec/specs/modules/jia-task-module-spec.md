# Task 任务调度模块规格文档

## 模块概述

Task 任务调度模块提供定时任务管理和执行日志功能。

- **子模块**: api, core, mapper, service, starter
- **包名**: cn.jia.task
- **版本**: 1.1.2-SNAPSHOT

## 核心功能

### 1. 定时任务管理

| 功能 | 说明 |
|------|------|
| 任务创建 | 创建定时任务 |
| 任务编辑 | 修改任务配置 |
| 任务删除 | 删除任务 |
| 任务启停 | 启动/停止任务 |
| 任务执行 | 手动触发执行 |

### 2. 任务调度

| 功能 | 说明 |
|------|------|
| Cron调度 | Cron表达式配置 |
| 间隔调度 | 固定间隔执行 |
| 一次性 | 单次执行任务 |
| 集群调度 | 分布式任务调度 |

### 3. 执行日志

| 功能 | 说明 |
|------|------|
| 执行记录 | 任务执行日志 |
| 执行状态 | 成功/失败状态 |
| 执行耗时 | 运行时间统计 |
| 错误信息 | 异常信息记录 |

## Service接口

### TaskService

```java
public interface TaskService {
    ScheduledTaskEntity create(ScheduledTaskEntity task);
    ScheduledTaskEntity getById(String id);
    void update(ScheduledTaskEntity task);
    void delete(String id);
    void enable(String id);
    void disable(String id);
    void execute(String id);
    List<ScheduledTaskEntity> listAll();
}
```

### TaskLogService

```java
public interface TaskLogService {
    void log(String taskId, Integer status, Long duration, String error);
    List<TaskLogEntity> listByTaskId(String taskId);
    TaskLogEntity getById(String id);
    void deleteOldLogs(int days);
}
```

## 相关模块

- **jia-common-core**: 提供基础实体
- **jia-base-core**: 系统日志
