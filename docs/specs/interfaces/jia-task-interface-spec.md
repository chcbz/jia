# Task 任务模块接口文档

来源模块：`task`

本文面向前端对接，每个接口包含请求方式、地址、参数位置、必填性、请求体字段和响应结构。

## 通用响应结构

- 单对象或操作结果：`JsonResult<T>`，字段：`code`、`msg`、`status`、`location`、`data`。
- 分页列表：`JsonResultPage<T>`，字段：`code`、`msg`、`status`、`pageNum`、`total`、`data`（数组）。
- 成功码默认为 `E0`，HTTP 状态通常为 `200`。

## 接口汇总

| 请求方式 | 地址 | Controller | 响应 |
| --- | --- | --- | --- |
| GET | `/job/list` | `JobController.list` | JsonResult<Map<String, JobHandler>> |
| GET | `/job/get` | `JobController.get` | JsonResult<Map<String, Object>> |
| POST | `/job/execute` | `JobController.execute` | JsonResult<String> |
| POST | `/job/execute/async` | `JobController.executeAsync` | [JsonResult](#model-jsonresult)<String\|Number\|Boolean> |
| GET | `/job/status` | `JobController.status` | JsonResult<Map<String, Object>> |
| GET | `/task/get` | `TaskController.findById` | JsonResult<[TaskPlanEntity](#model-taskplanentity)> |
| POST | `/task/create` | `TaskController.create` | JsonResult<Void> |
| POST | `/task/update` | `TaskController.update` | JsonResult<Void> |
| DELETE | `/task/delete` | `TaskController.delete` | JsonResult<Void> |
| DELETE | `/task/cancel` | `TaskController.cancel` | JsonResult<Void> |
| POST | `/task/search` | `TaskController.search` | JsonResultPage<[TaskPlanEntity](#model-taskplanentity)> |
| POST | `/task/item/search` | `TaskController.searchItem` | JsonResultPage<[TaskDetailEntity](#model-taskdetailentity)> |

## 接口明细

### JobController `/job`

源码：`task/jia-task-service/src/main/java/cn/jia/task/api/JobController.java`

#### GET `/job/list`

- 处理方法：`JobController.list`
- 响应：[JsonResult](#model-jsonresult)<Map<String, JobHandler>>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| - | - | - | - | 无入参 |

响应 data 说明：
- 按 `JsonResult<Map<String, JobHandler>>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/job/get`

- 处理方法：`JobController.get`
- 响应：[JsonResult](#model-jsonresult)<Map<String, Object>>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `name` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<Map<String, Object>>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/job/execute`

- 处理方法：`JobController.execute`
- 响应：[JsonResult](#model-jsonresult)<String>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `request` | body | yes | `JobExecuteRequest` | 请求体 |

请求体字段：

响应 data 说明：
- 按 `JsonResult<String>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/job/execute/async`

- 处理方法：`JobController.executeAsync`
- 响应：[JsonResult](#model-jsonresult)<String\|Number\|Boolean>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `request` | body | yes | `JobExecuteRequest` | 请求体 |

请求体字段：

响应 data 说明：
- 按 `JsonResult<String\|Number\|Boolean>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/job/status`

- 处理方法：`JobController.status`
- 响应：[JsonResult](#model-jsonresult)<Map<String, Object>>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `name` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<Map<String, Object>>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

### TaskController `/task`

源码：`task/jia-task-service/src/main/java/cn/jia/task/api/TaskController.java`

#### GET `/task/get`

- 处理方法：`TaskController.findById`
- 响应：[JsonResult](#model-jsonresult)<[TaskPlanEntity](#model-taskplanentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<TaskPlanEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/task/create`

- 处理方法：`TaskController.create`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `task` | body | yes | [TaskPlanEntity](#model-taskplanentity) | 请求体 |

请求体字段：

[TaskPlanEntity](#model-taskplanentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `TaskPlanEntity` |
| `updateTime` | `Long` | 源自 `TaskPlanEntity` |
| `tenantId` | `String` | 源自 `TaskPlanEntity` |
| `clientId` | `String` | 源自 `TaskPlanEntity` |
| `id` | `Long` | 源自 `TaskPlanEntity` |
| `jiacn` | `String` | 源自 `TaskPlanEntity` |
| `type` | `Integer` | 源自 `TaskPlanEntity` |
| `period` | `Integer` | 源自 `TaskPlanEntity` |
| `crond` | `String` | 源自 `TaskPlanEntity` |
| `name` | `String` | 源自 `TaskPlanEntity` |
| `description` | `String` | 源自 `TaskPlanEntity` |
| `lunar` | `Integer` | 源自 `TaskPlanEntity` |
| `startTime` | `Long` | 源自 `TaskPlanEntity` |
| `endTime` | `Long` | 源自 `TaskPlanEntity` |
| `amount` | `BigDecimal` | 源自 `TaskPlanEntity` |
| `remind` | `Integer` | 源自 `TaskPlanEntity` |
| `remindPhone` | `String` | 源自 `TaskPlanEntity` |
| `remindMsg` | `String` | 源自 `TaskPlanEntity` |
| `status` | `Integer` | 源自 `TaskPlanEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/task/update`

- 处理方法：`TaskController.update`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `task` | body | yes | [TaskPlanEntity](#model-taskplanentity) | 请求体 |

请求体字段：

[TaskPlanEntity](#model-taskplanentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `TaskPlanEntity` |
| `updateTime` | `Long` | 源自 `TaskPlanEntity` |
| `tenantId` | `String` | 源自 `TaskPlanEntity` |
| `clientId` | `String` | 源自 `TaskPlanEntity` |
| `id` | `Long` | 源自 `TaskPlanEntity` |
| `jiacn` | `String` | 源自 `TaskPlanEntity` |
| `type` | `Integer` | 源自 `TaskPlanEntity` |
| `period` | `Integer` | 源自 `TaskPlanEntity` |
| `crond` | `String` | 源自 `TaskPlanEntity` |
| `name` | `String` | 源自 `TaskPlanEntity` |
| `description` | `String` | 源自 `TaskPlanEntity` |
| `lunar` | `Integer` | 源自 `TaskPlanEntity` |
| `startTime` | `Long` | 源自 `TaskPlanEntity` |
| `endTime` | `Long` | 源自 `TaskPlanEntity` |
| `amount` | `BigDecimal` | 源自 `TaskPlanEntity` |
| `remind` | `Integer` | 源自 `TaskPlanEntity` |
| `remindPhone` | `String` | 源自 `TaskPlanEntity` |
| `remindMsg` | `String` | 源自 `TaskPlanEntity` |
| `status` | `Integer` | 源自 `TaskPlanEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### DELETE `/task/delete`

- 处理方法：`TaskController.delete`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### DELETE `/task/cancel`

- 处理方法：`TaskController.cancel`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/task/search`

- 处理方法：`TaskController.search`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[TaskPlanVO](#model-taskplanvo)> | 请求体 |

请求体字段：

[TaskPlanVO](#model-taskplanvo)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `TaskPlanVO` |
| `updateTime` | `Long` | 源自 `TaskPlanVO` |
| `tenantId` | `String` | 源自 `TaskPlanVO` |
| `clientId` | `String` | 源自 `TaskPlanVO` |
| `id` | `Long` | 源自 `TaskPlanVO` |
| `jiacn` | `String` | 源自 `TaskPlanVO` |
| `type` | `Integer` | 源自 `TaskPlanVO` |
| `period` | `Integer` | 源自 `TaskPlanVO` |
| `crond` | `String` | 源自 `TaskPlanVO` |
| `name` | `String` | 源自 `TaskPlanVO` |
| `description` | `String` | 源自 `TaskPlanVO` |
| `lunar` | `Integer` | 源自 `TaskPlanVO` |
| `startTime` | `Long` | 源自 `TaskPlanVO` |
| `endTime` | `Long` | 源自 `TaskPlanVO` |
| `amount` | `BigDecimal` | 源自 `TaskPlanVO` |
| `remind` | `Integer` | 源自 `TaskPlanVO` |
| `remindPhone` | `String` | 源自 `TaskPlanVO` |
| `remindMsg` | `String` | 源自 `TaskPlanVO` |
| `status` | `Integer` | 源自 `TaskPlanVO` |
| `startTimeStart` | `Long` | 源自 `TaskPlanVO` |
| `startTimeEnd` | `Long` | 源自 `TaskPlanVO` |
| `endTimeStart` | `Long` | 源自 `TaskPlanVO` |
| `endTimeEnd` | `Long` | 源自 `TaskPlanVO` |
| `historyFlag` | `Integer` | 源自 `TaskPlanVO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/task/item/search`

- 处理方法：`TaskController.searchItem`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[TaskDetailVO](#model-taskdetailvo)> | 请求体 |

请求体字段：

[TaskDetailVO](#model-taskdetailvo)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `TaskDetailVO` |
| `updateTime` | `Long` | 源自 `TaskDetailVO` |
| `tenantId` | `String` | 源自 `TaskDetailVO` |
| `clientId` | `String` | 源自 `TaskDetailVO` |
| `id` | `Long` | 源自 `TaskDetailVO` |
| `planId` | `Long` | 源自 `TaskDetailVO` |
| `jiacn` | `String` | 源自 `TaskDetailVO` |
| `type` | `Integer` | 源自 `TaskDetailVO` |
| `period` | `Integer` | 源自 `TaskDetailVO` |
| `crond` | `String` | 源自 `TaskDetailVO` |
| `name` | `String` | 源自 `TaskDetailVO` |
| `description` | `String` | 源自 `TaskDetailVO` |
| `amount` | `BigDecimal` | 源自 `TaskDetailVO` |
| `remind` | `Integer` | 源自 `TaskDetailVO` |
| `remindPhone` | `String` | 源自 `TaskDetailVO` |
| `remindMsg` | `String` | 源自 `TaskDetailVO` |
| `status` | `Integer` | 源自 `TaskDetailVO` |
| `executeTime` | `Long` | 源自 `TaskDetailVO` |
| `timeStart` | `Long` | 源自 `TaskDetailVO` |
| `timeEnd` | `Long` | 源自 `TaskDetailVO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

## 模型字段字典

下表列出本模块接口入参或出参涉及的本项目模型。未列出的第三方 SDK 类型请参考对应 SDK 定义。

### Model BaseEntity

源码：`common/jia-common-core/src/main/java/cn/jia/core/entity/BaseEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |

### Model JsonRequestPage

源码：`common/jia-common-core/src/main/java/cn/jia/core/entity/JsonRequestPage.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `draw` | `draw` | `Integer` |
| `pageNum` | `pageNum` | `Integer` |
| `pageSize` | `pageSize` | `Integer` |
| `search` | `search` | `T` |
| `extra_search` | `extraSearch` | `String` |
| `orderBy` | `orderBy` | `String` |

### Model JsonResult

源码：`common/jia-common-core/src/main/java/cn/jia/core/entity/JsonResult.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `status` | `status` | `int` |
| `location` | `location` | `String` |
| `data` | `data` | `T` |

### Model JsonResultPage

源码：`common/jia-common-core/src/main/java/cn/jia/core/entity/JsonResultPage.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `pageNum` | `pageNum` | `Integer` |
| `total` | `total` | `Long` |
| `status` | `status` | `int` |
| `data` | `data` | `List<T>` |

### Model TaskDetailVO

源码：`task/jia-task-api/src/main/java/cn/jia/task/entity/TaskDetailVO.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `planId` | `planId` | `Long` |
| `jiacn` | `jiacn` | `String` |
| `type` | `type` | `Integer` |
| `period` | `period` | `Integer` |
| `crond` | `crond` | `String` |
| `name` | `name` | `String` |
| `description` | `description` | `String` |
| `amount` | `amount` | `BigDecimal` |
| `remind` | `remind` | `Integer` |
| `remindPhone` | `remindPhone` | `String` |
| `remindMsg` | `remindMsg` | `String` |
| `status` | `status` | `Integer` |
| `executeTime` | `executeTime` | `Long` |
| `timeStart` | `timeStart` | `Long` |
| `timeEnd` | `timeEnd` | `Long` |

### Model TaskPlanEntity

源码：`task/jia-task-core/src/main/java/cn/jia/task/entity/TaskPlanEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `jiacn` | `jiacn` | `String` |
| `type` | `type` | `Integer` |
| `period` | `period` | `Integer` |
| `crond` | `crond` | `String` |
| `name` | `name` | `String` |
| `description` | `description` | `String` |
| `lunar` | `lunar` | `Integer` |
| `startTime` | `startTime` | `Long` |
| `endTime` | `endTime` | `Long` |
| `amount` | `amount` | `BigDecimal` |
| `remind` | `remind` | `Integer` |
| `remindPhone` | `remindPhone` | `String` |
| `remindMsg` | `remindMsg` | `String` |
| `status` | `status` | `Integer` |

### Model TaskPlanVO

源码：`task/jia-task-api/src/main/java/cn/jia/task/entity/TaskPlanVO.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `jiacn` | `jiacn` | `String` |
| `type` | `type` | `Integer` |
| `period` | `period` | `Integer` |
| `crond` | `crond` | `String` |
| `name` | `name` | `String` |
| `description` | `description` | `String` |
| `lunar` | `lunar` | `Integer` |
| `startTime` | `startTime` | `Long` |
| `endTime` | `endTime` | `Long` |
| `amount` | `amount` | `BigDecimal` |
| `remind` | `remind` | `Integer` |
| `remindPhone` | `remindPhone` | `String` |
| `remindMsg` | `remindMsg` | `String` |
| `status` | `status` | `Integer` |
| `startTimeStart` | `startTimeStart` | `Long` |
| `startTimeEnd` | `startTimeEnd` | `Long` |
| `endTimeStart` | `endTimeStart` | `Long` |
| `endTimeEnd` | `endTimeEnd` | `Long` |
| `historyFlag` | `historyFlag` | `Integer` |
