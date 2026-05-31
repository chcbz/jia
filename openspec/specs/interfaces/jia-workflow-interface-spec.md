# Workflow 工作流模块接口文档

来源模块：`workflow`

本文面向前端对接，每个接口包含请求方式、地址、参数位置、必填性、请求体字段和响应结构。

## 通用响应结构

- 单对象或操作结果：`JsonResult<T>`，字段：`code`、`msg`、`status`、`location`、`data`。
- 分页列表：`JsonResultPage<T>`，字段：`code`、`msg`、`status`、`pageNum`、`total`、`data`（数组）。
- 成功码默认为 `E0`，HTTP 状态通常为 `200`。

## 接口汇总

| 请求方式 | 地址 | Controller | 响应 |
| --- | --- | --- | --- |
| POST | `/workflow/deploy` | `WorkflowController.deployProcess` | JsonResult<Void> |
| POST | `/workflow/deployment/list` | `WorkflowController.getDeployment` | JsonResultPage<Map<String, Object>> |
| GET | `/workflow/deployment/find` | `WorkflowController.getDeploymentById` | JsonResult<Map<String, Object>> |
| GET | `/workflow/deployment/resource/list` | `WorkflowController.getDeploymentResourceNames` | JsonResult<List<String>> |
| GET | `/workflow/deployment/resource/find` | `WorkflowController.getDeploymentResource` | void |
| GET | `/workflow/deployment/delete` | `WorkflowController.deleteDeployment` | JsonResult<Void> |
| POST | `/workflow/definition/list` | `WorkflowController.getDefinition` | JsonResultPage<Map<String, Object>> |
| GET | `/workflow/definition/find` | `WorkflowController.getDefinitionById` | JsonResult<Map<String, Object>> |
| GET | `/workflow/definition/diagram` | `WorkflowController.getProcessDiagram` | void |
| GET | `/workflow/definition/activate` | `WorkflowController.activateDefinition` | JsonResult<Void> |
| GET | `/workflow/definition/suspend` | `WorkflowController.suspendDefinition` | JsonResult<Void> |
| POST | `/workflow/start/{processDefinitionKey}/{businessKey}` | `WorkflowController.startProcess` | JsonResult<Void> |
| POST | `/workflow/delete/{processInstanceId}` | `WorkflowController.deleteProcess` | JsonResult<Void> |
| POST | `/workflow/list/wait` | `WorkflowController.listWait` | JsonResultPage<Map<String, Object>> |
| POST | `/workflow/list/history` | `WorkflowController.listHistory` | JsonResultPage<Map<String, Object>> |
| POST | `/workflow/list/process` | `WorkflowController.listHistoryByBusinessKey` | JsonResultPage<Map<String, Object>> |
| POST | `/workflow/list/my` | `WorkflowController.listHistoricProcessInstances` | JsonResultPage<Map<String, Object>> |
| POST | `/workflow/complete/{taskId}` | `WorkflowController.complete` | JsonResult<Void> |
| GET | `/workflow/task/delegate` | `WorkflowController.delegateTask` | JsonResult<Void> |
| GET | `/workflow/task/claim` | `WorkflowController.claimTask` | JsonResult<Void> |
| GET | `/workflow/task/assign` | `WorkflowController.setAssignee` | JsonResult<Void> |
| GET | `/workflow/find/bybusinesskey` | `WorkflowController.getTaskByBusinessKey` | JsonResult<Map<String, Object>> |
| GET | `/workflow/list/bybusinesskey` | `WorkflowController.listTaskByBusinessKey` | JsonResult<List<Map<String, Object>>> |
| GET | `/workflow/find` | `WorkflowController.getTaskById` | JsonResult<Map<String, Object>> |
| GET | `/workflow/instance/diagram` | `WorkflowController.getInstanceDiagram` | void |

## 接口明细

### WorkflowController `/workflow`

源码：`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java`

#### POST `/workflow/deploy`

- 处理方法：`WorkflowController.deployProcess`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `file` | multipart | yes | MultipartFile | 文件/表单参数 |
| `deployment` | query/form | yes | Deployment | 查询/表单参数 |

请求体字段：

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/workflow/deployment/list`

- 处理方法：`WorkflowController.getDeployment`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[DeploymentExample](#model-deploymentexample)> | 请求体 |

请求体字段：

[DeploymentExample](#model-deploymentexample)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `name` | `String` | 源自 `DeploymentExample` |
| `category` | `String` | 源自 `DeploymentExample` |
| `key` | `String` | 源自 `DeploymentExample` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/workflow/deployment/find`

- 处理方法：`WorkflowController.getDeploymentById`
- 响应：[JsonResult](#model-jsonresult)<Map<String, Object>>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<Map<String, Object>>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/workflow/deployment/resource/list`

- 处理方法：`WorkflowController.getDeploymentResourceNames`
- 响应：[JsonResult](#model-jsonresult)<List<String>>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<List<String>>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/workflow/deployment/resource/find`

- 处理方法：`WorkflowController.getDeploymentResource`
- 响应：void

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `deploymentId` | query | yes | String | 查询/表单参数 |
| `resourceName` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/workflow/deployment/delete`

- 处理方法：`WorkflowController.deleteDeployment`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/workflow/definition/list`

- 处理方法：`WorkflowController.getDefinition`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[ProcessDefinitionExample](#model-processdefinitionexample)> | 请求体 |

请求体字段：

[ProcessDefinitionExample](#model-processdefinitionexample)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `name` | `String` | 源自 `ProcessDefinitionExample` |
| `description` | `String` | 源自 `ProcessDefinitionExample` |
| `key` | `String` | 源自 `ProcessDefinitionExample` |
| `version` | `int` | 源自 `ProcessDefinitionExample` |
| `category` | `String` | 源自 `ProcessDefinitionExample` |
| `deploymentId` | `String` | 源自 `ProcessDefinitionExample` |
| `resourceName` | `String` | 源自 `ProcessDefinitionExample` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/workflow/definition/find`

- 处理方法：`WorkflowController.getDefinitionById`
- 响应：[JsonResult](#model-jsonresult)<Map<String, Object>>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<Map<String, Object>>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/workflow/definition/diagram`

- 处理方法：`WorkflowController.getProcessDiagram`
- 响应：void

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/workflow/definition/activate`

- 处理方法：`WorkflowController.activateDefinition`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/workflow/definition/suspend`

- 处理方法：`WorkflowController.suspendDefinition`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/workflow/start/{processDefinitionKey}/{businessKey}`

- 处理方法：`WorkflowController.startProcess`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `processDefinitionKey` | path | yes | String | 路径参数 |
| `businessKey` | path | yes | String | 路径参数 |
| `variables` | body | yes | Map<String, Object> | 请求体 |

请求体字段：

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/workflow/delete/{processInstanceId}`

- 处理方法：`WorkflowController.deleteProcess`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `processInstanceId` | path | yes | String | 路径参数 |
| `deleteReason` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/workflow/list/wait`

- 处理方法：`WorkflowController.listWait`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[TaskExample](#model-taskexample)> | 请求体 |

请求体字段：

[TaskExample](#model-taskexample)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `name` | `String` | 源自 `TaskExample` |
| `taskDefinitionKey` | `String` | 源自 `TaskExample` |
| `assignee` | `String` | 源自 `TaskExample` |
| `candidateUser` | `String` | 源自 `TaskExample` |
| `candidateOrAssigned` | `String` | 源自 `TaskExample` |
| `processInstanceId` | `String` | 源自 `TaskExample` |
| `applicant` | `String` | 源自 `TaskExample` |
| `applicationDateStart` | `String` | 源自 `TaskExample` |
| `applicationDateEnd` | `String` | 源自 `TaskExample` |
| `businessKey` | `String` | 源自 `TaskExample` |
| `definitionKey` | `String` | 源自 `TaskExample` |
| `definitionName` | `String` | 源自 `TaskExample` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/workflow/list/history`

- 处理方法：`WorkflowController.listHistory`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[TaskExample](#model-taskexample)> | 请求体 |

请求体字段：

[TaskExample](#model-taskexample)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `name` | `String` | 源自 `TaskExample` |
| `taskDefinitionKey` | `String` | 源自 `TaskExample` |
| `assignee` | `String` | 源自 `TaskExample` |
| `candidateUser` | `String` | 源自 `TaskExample` |
| `candidateOrAssigned` | `String` | 源自 `TaskExample` |
| `processInstanceId` | `String` | 源自 `TaskExample` |
| `applicant` | `String` | 源自 `TaskExample` |
| `applicationDateStart` | `String` | 源自 `TaskExample` |
| `applicationDateEnd` | `String` | 源自 `TaskExample` |
| `businessKey` | `String` | 源自 `TaskExample` |
| `definitionKey` | `String` | 源自 `TaskExample` |
| `definitionName` | `String` | 源自 `TaskExample` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/workflow/list/process`

- 处理方法：`WorkflowController.listHistoryByBusinessKey`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<String> | 请求体 |

请求体字段：

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/workflow/list/my`

- 处理方法：`WorkflowController.listHistoricProcessInstances`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[ProcessInstanceExample](#model-processinstanceexample)> | 请求体 |

请求体字段：

[ProcessInstanceExample](#model-processinstanceexample)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `applicant` | `String` | 源自 `ProcessInstanceExample` |
| `definitionName` | `String` | 源自 `ProcessInstanceExample` |
| `definitionKey` | `String` | 源自 `ProcessInstanceExample` |
| `startedBefore` | `Date` | 源自 `ProcessInstanceExample` |
| `startedAfter` | `Date` | 源自 `ProcessInstanceExample` |
| `finishedBefore` | `Date` | 源自 `ProcessInstanceExample` |
| `finishedAfter` | `Date` | 源自 `ProcessInstanceExample` |
| `businessKey` | `String` | 源自 `ProcessInstanceExample` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/workflow/complete/{taskId}`

- 处理方法：`WorkflowController.complete`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `taskId` | path | yes | String | 路径参数 |
| `data` | body | yes | Map<String, Object> | 请求体 |

请求体字段：

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/workflow/task/delegate`

- 处理方法：`WorkflowController.delegateTask`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `taskId` | query | yes | String | 查询/表单参数 |
| `userId` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/workflow/task/claim`

- 处理方法：`WorkflowController.claimTask`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `taskId` | query/form | yes | String | 查询/表单参数 |
| `userId` | query/form | yes | String | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/workflow/task/assign`

- 处理方法：`WorkflowController.setAssignee`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `taskId` | query/form | yes | String | 查询/表单参数 |
| `userId` | query/form | yes | String | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/workflow/find/bybusinesskey`

- 处理方法：`WorkflowController.getTaskByBusinessKey`
- 响应：[JsonResult](#model-jsonresult)<Map<String, Object>>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `businessKey` | query | yes | String | 查询/表单参数 |
| `assignee` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<Map<String, Object>>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/workflow/list/bybusinesskey`

- 处理方法：`WorkflowController.listTaskByBusinessKey`
- 响应：[JsonResult](#model-jsonresult)<List<Map<String, Object>>>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `businessKey` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<List<Map<String, Object>>>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/workflow/find`

- 处理方法：`WorkflowController.getTaskById`
- 响应：[JsonResult](#model-jsonresult)<Map<String, Object>>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `taskId` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<Map<String, Object>>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/workflow/instance/diagram`

- 处理方法：`WorkflowController.getInstanceDiagram`
- 响应：void

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | String | 查询/表单参数 |

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

### Model DeploymentExample

源码：`workflow/jia-workflow-core/src/main/java/cn/jia/workflow/entity/DeploymentExample.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `name` | `name` | `String` |
| `category` | `category` | `String` |
| `key` | `key` | `String` |

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

### Model ProcessDefinitionExample

源码：`workflow/jia-workflow-core/src/main/java/cn/jia/workflow/entity/ProcessDefinitionExample.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `name` | `name` | `String` |
| `description` | `description` | `String` |
| `key` | `key` | `String` |
| `version` | `version` | `int` |
| `category` | `category` | `String` |
| `deploymentId` | `deploymentId` | `String` |
| `resourceName` | `resourceName` | `String` |

### Model ProcessInstanceExample

源码：`workflow/jia-workflow-core/src/main/java/cn/jia/workflow/entity/ProcessInstanceExample.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `applicant` | `applicant` | `String` |
| `definitionName` | `definitionName` | `String` |
| `definitionKey` | `definitionKey` | `String` |
| `startedBefore` | `startedBefore` | `Date` |
| `startedAfter` | `startedAfter` | `Date` |
| `finishedBefore` | `finishedBefore` | `Date` |
| `finishedAfter` | `finishedAfter` | `Date` |
| `businessKey` | `businessKey` | `String` |

### Model TaskExample

源码：`workflow/jia-workflow-core/src/main/java/cn/jia/workflow/entity/TaskExample.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `name` | `name` | `String` |
| `taskDefinitionKey` | `taskDefinitionKey` | `String` |
| `assignee` | `assignee` | `String` |
| `candidateUser` | `candidateUser` | `String` |
| `candidateOrAssigned` | `candidateOrAssigned` | `String` |
| `processInstanceId` | `processInstanceId` | `String` |
| `applicant` | `applicant` | `String` |
| `applicationDateStart` | `applicationDateStart` | `String` |
| `applicationDateEnd` | `applicationDateEnd` | `String` |
| `businessKey` | `businessKey` | `String` |
| `definitionKey` | `definitionKey` | `String` |
| `definitionName` | `definitionName` | `String` |
