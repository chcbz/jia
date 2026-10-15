# workflow：逐HTTP接口源码清单

基线 `7e2d07a05b9e9b18461e97abcff44642504320ff`；25 个method/path条目。源代码声明不是目标契约已完成。公共封装/权限/幂等见[目录说明](README.md)，人工复核关键链见[逐接口契约](critical-contracts.md)。

## `POST /workflow/deploy`

- operationId：`WorkflowController.deployProcess.POST`
- 源码：`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:53`
- 标记：MULTIPART, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object deployProcess(@RequestPart("file") MultipartFile file, Deployment deployment)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /workflow/deployment/list`

- operationId：`WorkflowController.getDeployment.POST`
- 源码：`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:69`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object getDeployment(@RequestBody JsonRequestPage<DeploymentExample> page)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /workflow/deployment/find`

- operationId：`WorkflowController.getDeploymentById.GET`
- 源码：`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:95`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object getDeploymentById(@RequestParam String id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /workflow/deployment/resource/list`

- operationId：`WorkflowController.getDeploymentResourceNames.GET`
- 源码：`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:114`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object getDeploymentResourceNames(@RequestParam String id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /workflow/deployment/resource/find`

- operationId：`WorkflowController.getDeploymentResource.GET`
- 源码：`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:132`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public void getDeploymentResource(@RequestParam String deploymentId, @RequestParam String resourceName, HttpServletRequest request, HttpServletResponse response)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /workflow/deployment/delete`

- operationId：`WorkflowController.deleteDeployment.GET`
- 源码：`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:152`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object deleteDeployment(@RequestParam String id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /workflow/definition/list`

- operationId：`WorkflowController.getDefinition.POST`
- 源码：`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:164`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object getDefinition(@RequestBody JsonRequestPage<ProcessDefinitionExample> page)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /workflow/definition/find`

- operationId：`WorkflowController.getDefinitionById.GET`
- 源码：`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:195`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object getDefinitionById(@RequestParam String id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /workflow/definition/diagram`

- operationId：`WorkflowController.getProcessDiagram.GET`
- 源码：`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:223`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public void getProcessDiagram(@RequestParam String id, HttpServletRequest request, HttpServletResponse response)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /workflow/definition/activate`

- operationId：`WorkflowController.activateDefinition.GET`
- 源码：`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:243`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object activateDefinition(@RequestParam String id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /workflow/definition/suspend`

- operationId：`WorkflowController.suspendDefinition.GET`
- 源码：`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:255`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object suspendDefinition(@RequestParam String id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /workflow/start/{processDefinitionKey}/{businessKey}`

- operationId：`WorkflowController.startProcess.POST`
- 源码：`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:269`
- 标记：RAW_OR_DYNAMIC_BODY, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object startProcess(@PathVariable String processDefinitionKey, @PathVariable String businessKey, @RequestBody Map<String, Object> variables)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /workflow/delete/{processInstanceId}`

- operationId：`WorkflowController.deleteProcess.POST`
- 源码：`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:285`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object deleteProcess(@PathVariable String processInstanceId, @RequestParam String deleteReason)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /workflow/list/wait`

- operationId：`WorkflowController.listWait.POST`
- 源码：`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:297`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object listWait(@RequestBody JsonRequestPage<TaskExample> page)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /workflow/list/history`

- operationId：`WorkflowController.listHistory.POST`
- 源码：`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:335`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object listHistory(@RequestBody JsonRequestPage<TaskExample> page)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /workflow/list/process`

- operationId：`WorkflowController.listHistoryByBusinessKey.POST`
- 源码：`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:370`
- 标记：RAW_OR_DYNAMIC_BODY, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object listHistoryByBusinessKey(@RequestBody JsonRequestPage<String> page)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /workflow/list/my`

- operationId：`WorkflowController.listHistoricProcessInstances.POST`
- 源码：`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:412`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object listHistoricProcessInstances(@RequestBody JsonRequestPage<ProcessInstanceExample> page, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /workflow/complete/{taskId}`

- operationId：`WorkflowController.complete.POST`
- 源码：`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:462`
- 标记：RAW_OR_DYNAMIC_BODY, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object complete(@PathVariable String taskId, @RequestBody Map<String, Object> data)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /workflow/task/delegate`

- operationId：`WorkflowController.delegateTask.GET`
- 源码：`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:490`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object delegateTask(@RequestParam String taskId, @RequestParam String userId)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /workflow/task/claim`

- operationId：`WorkflowController.claimTask.GET`
- 源码：`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:502`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object claimTask(String taskId, String userId)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /workflow/task/assign`

- operationId：`WorkflowController.setAssignee.GET`
- 源码：`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:515`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object setAssignee(String taskId, String userId)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /workflow/find/bybusinesskey`

- operationId：`WorkflowController.getTaskByBusinessKey.GET`
- 源码：`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:528`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object getTaskByBusinessKey(@RequestParam String businessKey, @RequestParam String assignee)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /workflow/list/bybusinesskey`

- operationId：`WorkflowController.listTaskByBusinessKey.GET`
- 源码：`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:560`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object listTaskByBusinessKey(@RequestParam String businessKey)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /workflow/find`

- operationId：`WorkflowController.getTaskById.GET`
- 源码：`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:587`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object getTaskById(@RequestParam String taskId)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /workflow/instance/diagram`

- operationId：`WorkflowController.getInstanceDiagram.GET`
- 源码：`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:621`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public void getInstanceDiagram(@RequestParam String id, HttpServletRequest request, HttpServletResponse response)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

