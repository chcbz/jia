# agent：逐HTTP接口源码清单

基线 `7e2d07a05b9e9b18461e97abcff44642504320ff`；179 个method/path条目。源代码声明不是目标契约已完成。公共封装/权限/幂等见[目录说明](README.md)，人工复核关键链见[逐接口契约](critical-contracts.md)。

## `POST /agent/hall/collaboration/requests`

- operationId：`AgentCollaborationRequestController.submit.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentCollaborationRequestController.java:32`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<AgentCollaborationRequestService.RequestView> submit( @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/hall/collaboration/requests/{requestId}`

- operationId：`AgentCollaborationRequestController.get.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentCollaborationRequestController.java:51`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<AgentCollaborationRequestService.RequestView> get( @PathVariable String requestId, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/hall/collaboration/requests/request`

- operationId：`AgentCollaborationRequestController.request.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentCollaborationRequestController.java:58`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<AgentCollaborationRequestService.RequestView> request( @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/internal/command-operations/capabilities`

- operationId：`AgentCommandOperationsCapabilitiesController.capabilities.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentCommandOperationsCapabilitiesController.java:34`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<CapabilityBody>> capabilities(Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/internal/command-operations/metrics`

- operationId：`AgentCommandOperationsController.metrics.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentCommandOperationsController.java:74`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<?> metrics(Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/internal/command-operations/dlq`

- operationId：`AgentCommandOperationsController.dlq.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentCommandOperationsController.java:80`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<?> dlq( @RequestParam(defaultValue = "0") String afterDeliveryId, @RequestParam(defaultValue = "50") String limit, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/internal/command-operations/audit`

- operationId：`AgentCommandOperationsController.audit.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentCommandOperationsController.java:90`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<?> audit( @RequestParam(defaultValue = "0") String afterId, @RequestParam(defaultValue = "50") String limit, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/internal/command-operations/v1/operations/{operationId}`

- operationId：`AgentCommandOperationsController.operationV1.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentCommandOperationsController.java:100`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<?> operationV1( @PathVariable String operationId, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/internal/command-operations/v1/dlq/{deliveryId}/redrive`

- operationId：`AgentCommandOperationsController.redriveV1.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentCommandOperationsController.java:110`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<?> redriveV1( @PathVariable String deliveryId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody byte[] rawBody, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/internal/command-operations/dlq/{deliveryId}/redrive`

- operationId：`AgentCommandOperationsController.redrive.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentCommandOperationsController.java:124`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<?> redrive( @PathVariable String deliveryId, @RequestBody byte[] rawBody, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/internal/command-operations/deliveries/{deliveryId}/reissue`

- operationId：`AgentCommandOperationsController.reissue.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentCommandOperationsController.java:136`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<?> reissue( @PathVariable String deliveryId, @RequestBody byte[] rawBody, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/register`

- operationId：`AgentController.register.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:152`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object register(@RequestBody AgentRegisterDTO request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/list`

- operationId：`AgentController.list.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:157`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object list(@RequestParam(required = false) String status, @RequestParam(required = false) String ability, @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "50") int pageSize)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/map`

- operationId：`AgentController.mapAgents.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:165`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object mapAgents()
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/capabilities`

- operationId：`AgentController.capabilities.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:170`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object capabilities()
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/personas/catalog`

- operationId：`AgentController.personaCatalog.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:176`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object personaCatalog(Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/personas/{personaCode}/bind`

- operationId：`AgentController.bindPersonaHttp.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:185`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object bindPersonaHttp(@PathVariable String personaCode, Authentication authentication, HttpServletRequest servletRequest, @RequestBody(required = false) byte[] rawBody)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/personas/bindings/{bindingId}/repair`

- operationId：`AgentController.repairPersonaBinding.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:238`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object repairPersonaBinding(@PathVariable long bindingId, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `DELETE /agent/personas/{personaCode}/bind`

- operationId：`AgentController.unbindPersona.DELETE`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:244`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object unbindPersona(@PathVariable String personaCode, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/roster`

- operationId：`AgentController.roster.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:289`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object roster(@RequestBody AgentRosterSearchDTO request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/{agentId}`

- operationId：`AgentController.get.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:299`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object get(@PathVariable String agentId)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `PUT /agent/{agentId}/status`

- operationId：`AgentController.updateStatus.PUT`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:310`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object updateStatus(@PathVariable String agentId, @RequestBody AgentStatusDTO request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/{agentId}/tasks`

- operationId：`AgentController.getTasks.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:315`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object getTasks(@PathVariable String agentId)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/personas`

- operationId：`AgentController.personas.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:320`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object personas()
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/persona/{name}`

- operationId：`AgentController.persona.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:325`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object persona(@PathVariable String name)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/dialogue`

- operationId：`AgentController.dialogue.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:330`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object dialogue(@RequestBody DialogueRequestDTO request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/stats`

- operationId：`AgentController.stats.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:335`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object stats(@RequestParam String agentId)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/search`

- operationId：`AgentController.searchTasks.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:340`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object searchTasks(@RequestBody AgentTaskSearchDTO request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/status-counts`

- operationId：`AgentController.countTasksByStatus.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:345`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object countTasksByStatus(@RequestBody AgentTaskSearchDTO request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks`

- operationId：`AgentController.createTask.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:350`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object createTask(@RequestBody AgentTaskCreateDTO request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/{taskId}`

- operationId：`AgentController.getTask.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:363`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object getTask(@PathVariable String taskId)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/funding/cancel`

- operationId：`AgentController.cancelTaskFunding.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:368`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object cancelTaskFunding(@PathVariable String taskId, @RequestBody AgentTaskFundingCancelDTO request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/quotes`

- operationId：`AgentController.quoteTask.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:381`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object quoteTask(@PathVariable String taskId, @RequestBody AgentTaskQuoteRequestDTO request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/claim`

- operationId：`AgentController.claimTask.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:392`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object claimTask(@PathVariable String taskId, @RequestBody AgentTaskClaimRequestDTO request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/assign`

- operationId：`AgentController.assignTask.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:403`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object assignTask(@PathVariable String taskId, @RequestBody AgentTaskAssignDTO request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/execution-authorizations/{grantId}/revoke`

- operationId：`AgentController.revokeTaskExecutionGrant.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:416`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object revokeTaskExecutionGrant(@PathVariable String taskId, @PathVariable String grantId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody AgentTaskExecutionGrantRevokeDTO request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/recommend`

- operationId：`AgentController.recommendTaskAssignees.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:431`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object recommendTaskAssignees(@PathVariable String taskId)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/team-recommendation`

- operationId：`AgentController.recommendTaskTeam.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:437`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object recommendTaskTeam( @PathVariable String taskId, @RequestBody AgentTaskTeamRecommendationRequestDTO request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/auto-assign`

- operationId：`AgentController.autoAssignTask.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:450`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object autoAssignTask(@PathVariable String taskId, @RequestBody(required = false) AgentTaskAssignDTO request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/report`

- operationId：`AgentController.reportTask.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:455`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object reportTask(@PathVariable String taskId, @RequestBody AgentTaskReportDTO request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/notes`

- operationId：`AgentController.addTaskNote.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:460`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object addTaskNote(@PathVariable String taskId, @RequestBody AgentTaskNoteDTO request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/{taskId}/notes`

- operationId：`AgentController.listTaskNotes.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:465`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object listTaskNotes(@PathVariable String taskId)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/archive`

- operationId：`AgentController.archiveTask.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:470`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object archiveTask(@PathVariable String taskId)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/evaluate`

- operationId：`AgentController.evaluate.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:475`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object evaluate(@RequestBody AbilityEvaluationRequestDTO request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/compare`

- operationId：`AgentController.compare.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:480`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object compare(@RequestBody AbilityCompareRequestDTO request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/evaluation/{agentName}`

- operationId：`AgentController.evaluationHistory.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:485`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object evaluationHistory(@PathVariable String agentName)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/evaluation/latest/{agentName}`

- operationId：`AgentController.latestEvaluation.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:490`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object latestEvaluation(@PathVariable String agentName)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/evaluation/stats`

- operationId：`AgentController.evaluationStats.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:495`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object evaluationStats()
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `DELETE /agent/evaluation/{id}`

- operationId：`AgentController.deleteEvaluation.DELETE`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:500`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object deleteEvaluation(@PathVariable Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/personas/{personaCode}/hosting-rent/quotes`

- operationId：`AgentHostingRentController.quote.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentHostingRentController.java:61`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object quote( @PathVariable String personaCode, Authentication authentication, HttpServletRequest request, @RequestBody(required = false) byte[] rawBody)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/{agentId}/hosting-lease`

- operationId：`AgentHostingRentController.lease.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentHostingRentController.java:79`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object lease(@PathVariable String agentId, Authentication authentication, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/hosting-leases/{leaseId}/renewal-quotes`

- operationId：`AgentHostingRentController.renewalQuote.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentHostingRentController.java:85`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object renewalQuote(@PathVariable String leaseId, Authentication authentication, HttpServletRequest request, @RequestBody byte[] body)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/hosting-leases/{leaseId}/renewals`

- operationId：`AgentHostingRentController.renew.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentHostingRentController.java:93`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object renew(@PathVariable String leaseId, Authentication authentication, HttpServletRequest request, @RequestBody byte[] body)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/runtime/v1/installations`

- operationId：`AgentRuntimeV1Controller.create.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentRuntimeV1Controller.java:40`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<AgentRuntimeV1InstallationView>> create( @RequestBody AgentRuntimeV1InstallationRequest request, Authentication authentication, HttpServletRequest servletRequest)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/runtime/v1/installations/{installationId}`

- operationId：`AgentRuntimeV1Controller.status.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentRuntimeV1Controller.java:49`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<AgentRuntimeV1InstallationView>> status( @PathVariable String installationId, Authentication authentication, HttpServletRequest servletRequest)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/runtime/v1/installations/{installationId}/revoke`

- operationId：`AgentRuntimeV1Controller.revoke.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentRuntimeV1Controller.java:57`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<Void>> revoke(@PathVariable String installationId, Authentication authentication, HttpServletRequest servletRequest)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/runtime/v1/enroll`

- operationId：`AgentRuntimeV1Controller.enroll.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentRuntimeV1Controller.java:67`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<AgentRuntimeV1EnrollmentResult>> enroll( @RequestBody AgentRuntimeV1EnrollmentRequest request, HttpServletRequest servletRequest)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/runtime/v1/session`

- operationId：`AgentRuntimeV1Controller.session.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentRuntimeV1Controller.java:76`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<AgentRuntimeV1SessionResponse>> session( @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization, @RequestBody AgentRuntimeV1SessionRequest request, HttpServletRequest servletRequest)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/runtime/v1/heartbeat`

- operationId：`AgentRuntimeV1Controller.heartbeat.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentRuntimeV1Controller.java:84`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<AgentRuntimeV1InstallationView>> heartbeat( @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization, @RequestBody AgentRuntimeV1RuntimeRequest request, HttpServletRequest servletRequest)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/runtime/v1/commands/{messageId}/acks`

- operationId：`AgentRuntimeV1Controller.acknowledge.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentRuntimeV1Controller.java:92`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<AgentCommandAckResult>> acknowledge(@PathVariable String messageId, @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization, @RequestBody AgentRuntimeV1AckRequest request, HttpServletRequest servletRequest)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/scenes/{sceneId}/snapshot`

- operationId：`AgentSceneController.snapshot.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentSceneController.java:48`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public JsonResult<AgentSceneSnapshotDTO> snapshot(@PathVariable String sceneId)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/scenes/{sceneId}/events`

- operationId：`AgentSceneController.events.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentSceneController.java:58`
- 标记：STREAM
- 当前声明（含入参位置、Java类型及默认值）：

```java
public SseEmitter events( @PathVariable String sceneId, @RequestParam(name = "sinceVersion", required = false) Long sinceVersion, @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId)
```

- 目标封装规则：显式字节/流契约；禁止JSON包装已开始传输的内容。错误、HEAD/Range/断流按专属契约定义。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/scenes/{sceneId}/phases`

- operationId：`AgentSceneController.phase.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentSceneController.java:80`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public JsonResult<AgentScenePhaseResultDTO> phase( @PathVariable String sceneId, @RequestBody(required = false) AgentScenePhaseReportDTO request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/artifacts`

- operationId：`AgentTaskArtifactController.publish.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskArtifactController.java:72`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<AgentTaskArtifactPublishResponse> publish( @PathVariable String taskId, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/{taskId}/artifacts/{artifactId}/versions/{artifactVersion}/content`

- operationId：`AgentTaskArtifactController.download.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskArtifactController.java:115`
- 标记：BYTE_RESPONSE_REQUIRES_MEDIA_CHECK
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<byte[]> download( @PathVariable String taskId, @PathVariable String artifactId, @PathVariable String artifactVersion, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：返回byte[]不等于二进制文件：先核实际Content-Type/序列化。若为自有application/json仍必须统一信封；真实文件才走字节例外。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/artifact-outcomes/accept`

- operationId：`AgentTaskArtifactOutcomeController.accept.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskArtifactOutcomeController.java:87`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<AgentTaskArtifactOutcomeResponse> accept( @PathVariable String taskId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/{taskId}/artifact-outcomes/accepted`

- operationId：`AgentTaskArtifactOutcomeController.accepted.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskArtifactOutcomeController.java:111`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<List<AgentTaskArtifactOutcomeResponse>> accepted( @PathVariable String taskId, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/cancel`

- operationId：`AgentTaskCancellationController.cancel.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskCancellationController.java:33`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<AgentTaskCancellationService.Receipt> cancel(@PathVariable String taskId, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/{taskId}/context-pack`

- operationId：`AgentTaskContextPackController.contextPack.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskContextPackController.java:49`
- 标记：BYTE_RESPONSE_REQUIRES_MEDIA_CHECK
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<byte[]> contextPack(@PathVariable String taskId, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：返回byte[]不等于二进制文件：先核实际Content-Type/序列化。若为自有application/json仍必须统一信封；真实文件才走字节例外。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/creation-operations`

- operationId：`AgentTaskCreationOperationController.create.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskCreationOperationController.java:60`
- 标记：RAW_OR_DYNAMIC_BODY
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<AgentTaskCreationOperationService.Receipt> create( @RequestBody(required = false) String rawBody, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/creation-operations/request`

- operationId：`AgentTaskCreationOperationController.request.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskCreationOperationController.java:80`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<AgentTaskCreationOperationService.Receipt> request( @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/creation-operations/v2`

- operationId：`AgentTaskCreationOperationController.createV2.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskCreationOperationController.java:91`
- 标记：RAW_OR_DYNAMIC_BODY
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<AgentTaskCreationOperationService.MaterialsReceipt> createV2( @RequestBody(required = false) String rawBody, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/creation-operations/v2/request`

- operationId：`AgentTaskCreationOperationController.requestV2.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskCreationOperationController.java:107`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<AgentTaskCreationOperationService.MaterialsReceipt> requestV2( @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/{taskId}/assignment-operation`

- operationId：`AgentTaskDeliberationOperationController.read.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskDeliberationOperationController.java:61`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<AssignmentOperationView>> read( @PathVariable String taskId, @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/point-and-deliberate`

- operationId：`AgentTaskDeliberationOperationController.point.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskDeliberationOperationController.java:76`
- 标记：RAW_OR_DYNAMIC_BODY
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<AssignmentOperationView>> point( @PathVariable String taskId, @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody(required = false) String rawBody, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/{taskId}/point-and-deliberate/request`

- operationId：`AgentTaskDeliberationOperationController.pointRequest.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskDeliberationOperationController.java:92`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<AssignmentOperationView>> pointRequest( @PathVariable String taskId, @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/{taskId}/deliverables`

- operationId：`AgentTaskDeliverableController.list.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskDeliverableController.java:65`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<DeliverableListResponse> list( @PathVariable String taskId, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/{taskId}/deliverables/{artifactId}/versions/{artifactVersion}/content`

- operationId：`AgentTaskDeliverableController.download.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskDeliverableController.java:82`
- 标记：BYTE_RESPONSE_REQUIRES_MEDIA_CHECK
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<byte[]> download( @PathVariable String taskId, @PathVariable String artifactId, @PathVariable String artifactVersion, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：返回byte[]不等于二进制文件：先核实际Content-Type/序列化。若为自有application/json仍必须统一信封；真实文件才走字节例外。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/{taskId}/deliverables/{artifactId}/versions/{artifactVersion}/preview`

- operationId：`AgentTaskDeliverableController.preview.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskDeliverableController.java:103`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PreviewResponse> preview( @PathVariable String taskId, @PathVariable String artifactId, @PathVariable String artifactVersion, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/{taskId}/deliverables/{artifactId}/versions/{artifactVersion}/preview/parts/{partId}`

- operationId：`AgentTaskDeliverableController.previewContent.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskDeliverableController.java:127`
- 标记：BYTE_RESPONSE_REQUIRES_MEDIA_CHECK
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<byte[]> previewContent( @PathVariable String taskId, @PathVariable String artifactId, @PathVariable String artifactVersion, @PathVariable String partId, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：返回byte[]不等于二进制文件：先核实际Content-Type/序列化。若为自有application/json仍必须统一信封；真实文件才走字节例外。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/{taskId}/events`

- operationId：`AgentTaskEventStreamController.events.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskEventStreamController.java:63`
- 标记：STREAM
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<SseEmitter> events( @PathVariable String taskId, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：显式字节/流契约；禁止JSON包装已开始传输的内容。错误、HEAD/Range/断流按专属契约定义。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/{taskId}/formal-deliveries`

- operationId：`AgentTaskFormalDeliveryController.list.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskFormalDeliveryController.java:93`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<FormalDeliveryListResponse> list(@PathVariable String taskId, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/formal-deliveries`

- operationId：`AgentTaskFormalDeliveryController.submit.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskFormalDeliveryController.java:114`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<FormalDeliveryResponse> submit(@PathVariable String taskId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/formal-deliveries/{deliveryId}/decision`

- operationId：`AgentTaskFormalDeliveryController.decide.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskFormalDeliveryController.java:130`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<FormalDeliveryResponse> decide(@PathVariable String taskId, @PathVariable String deliveryId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/formal-deliveries/{deliveryId}/rework-executions`

- operationId：`AgentTaskFormalDeliveryController.createReworkExecution.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskFormalDeliveryController.java:147`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceExecutionService.ExecutionView> createReworkExecution( @PathVariable String taskId, @PathVariable String deliveryId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/{taskId}/point-and-start-cost-consents/{consentId}`

- operationId：`AgentTaskProviderCostConsentController.get.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskProviderCostConsentController.java:80`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<AgentTaskProviderCostConsentDTO> get(@PathVariable String taskId, @PathVariable String consentId,HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/{taskId}/point-and-start-cost-consents/request`

- operationId：`AgentTaskProviderCostConsentController.request.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskProviderCostConsentController.java:89`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<AgentTaskProviderCostConsentDTO> request(@PathVariable String taskId, @RequestHeader(value="Idempotency-Key",required=false) String key, HttpServletRequest request,Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/{taskId}/requirements/current`

- operationId：`AgentTaskRequirementSnapshotController.current.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskRequirementSnapshotController.java:41`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<CurrentRequirementView>> current( @PathVariable String taskId, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/{taskId}/workspace`

- operationId：`AgentTaskWorkspaceController.workspace.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskWorkspaceController.java:40`
- 标记：BYTE_RESPONSE_REQUIRES_MEDIA_CHECK
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<byte[]> workspace(@PathVariable String taskId, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：返回byte[]不等于二进制文件：先核实际Content-Type/序列化。若为自有application/json仍必须统一信封；真实文件才走字节例外。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/work-item-plans/suggest`

- operationId：`AgentWorkItemPlanController.suggest.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentWorkItemPlanController.java:56`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<AgentWorkItemPlanViewDTO> suggest( @PathVariable String taskId, @RequestBody byte[] body, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/work-item-plans/confirm`

- operationId：`AgentWorkItemPlanController.confirm.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentWorkItemPlanController.java:72`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<AgentWorkItemPlanViewDTO> confirm( @PathVariable String taskId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody byte[] body, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/work-items/{workItemId}/reassignments`

- operationId：`AgentWorkItemReassignmentController.reassign.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentWorkItemReassignmentController.java:63`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<AgentWorkItemReassignmentResultDTO> reassign( @PathVariable String taskId, @PathVariable String workItemId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/work-items/{workItemId}/reassignments/{reassignmentId}/lease`

- operationId：`AgentWorkItemReassignmentController.readLease.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentWorkItemReassignmentController.java:81`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<AgentWorkItemReassignmentLeaseDTO> readLease( @PathVariable String taskId, @PathVariable String workItemId, @PathVariable String reassignmentId, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/work-items/{workItemId}/reassignments/{reassignmentId}/lease/start`

- operationId：`AgentWorkItemReassignmentController.startLease.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentWorkItemReassignmentController.java:99`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<AgentWorkItemReassignmentLeaseDTO> startLease( @PathVariable String taskId, @PathVariable String workItemId, @PathVariable String reassignmentId, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/work-items/{workItemId}/reassignments/{reassignmentId}/lease/heartbeat`

- operationId：`AgentWorkItemReassignmentController.heartbeatLease.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentWorkItemReassignmentController.java:117`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<AgentWorkItemReassignmentLeaseDTO> heartbeatLease( @PathVariable String taskId, @PathVariable String workItemId, @PathVariable String reassignmentId, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /internal/agent/tasks/{taskId}/work-items/{workItemId}/reassignments/{reassignmentId}/commands/{commandId}/result-commit`

- operationId：`AgentWorkItemRuntimeResultController.commit.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentWorkItemRuntimeResultController.java:52`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<AgentWorkItemResultCommitViewDTO> commit(@PathVariable String taskId, @PathVariable String workItemId, @PathVariable String reassignmentId, @PathVariable String commandId, HttpServletRequest request, Authentication principal)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /internal/agent/tasks/{taskId}/work-items/{workItemId}/reassignments/{reassignmentId}/commands/{commandId}/result-commit`

- operationId：`AgentWorkItemRuntimeResultController.readResult.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentWorkItemRuntimeResultController.java:71`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<AgentWorkItemResultCommitViewDTO> readResult(@PathVariable String taskId, @PathVariable String workItemId, @PathVariable String reassignmentId, @PathVariable String commandId, HttpServletRequest request, Authentication principal)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /internal/agent/tasks/{taskId}/work-items/{workItemId}/reassignments/{reassignmentId}/commands/{commandId}/lease`

- operationId：`AgentWorkItemRuntimeResultController.readLease.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentWorkItemRuntimeResultController.java:82`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<AgentWorkItemReassignmentLeaseDTO> readLease(@PathVariable String taskId, @PathVariable String workItemId, @PathVariable String reassignmentId, @PathVariable String commandId, HttpServletRequest request, Authentication principal)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/{taskId}/point-and-start-controlled-image/request`

- operationId：`ControlledImagePointAndStartController.get.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/ControlledImagePointAndStartController.java:69`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<ControlledImagePointAndStartDTO.Receipt>> get( @PathVariable String taskId, @RequestHeader(value="Idempotency-Key",required=false) String key, HttpServletRequest request,Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /economy/preview/capabilities`

- operationId：`EconomyReadOnlyPreviewController.capabilities.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/EconomyReadOnlyPreviewController.java:54`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PreviewResult<Object>> capabilities(Authentication authentication, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /economy/preview/wallet`

- operationId：`EconomyReadOnlyPreviewController.wallet.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/EconomyReadOnlyPreviewController.java:62`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PreviewResult<Object>> wallet(Authentication authentication, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /economy/preview/ledger`

- operationId：`EconomyReadOnlyPreviewController.ledger.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/EconomyReadOnlyPreviewController.java:70`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PreviewResult<Object>> ledger(Authentication authentication, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /economy/preview/skill-products`

- operationId：`EconomyReadOnlyPreviewController.products.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/EconomyReadOnlyPreviewController.java:78`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PreviewResult<Object>> products(Authentication authentication, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /economy/preview/skill-products/{productId}`

- operationId：`EconomyReadOnlyPreviewController.product.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/EconomyReadOnlyPreviewController.java:86`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PreviewResult<Object>> product(@PathVariable String productId, Authentication authentication, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /economy/preview/agents/{agentId}/skills`

- operationId：`EconomyReadOnlyPreviewController.agentSkills.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/EconomyReadOnlyPreviewController.java:95`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PreviewResult<Object>> agentSkills(@PathVariable String agentId, Authentication authentication, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /economy/preview/hosting-plan`

- operationId：`EconomyReadOnlyPreviewController.hostingPlan.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/EconomyReadOnlyPreviewController.java:104`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PreviewResult<Object>> hostingPlan(Authentication authentication, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /economy/preview/agents/{agentId}/hosting-lease`

- operationId：`EconomyReadOnlyPreviewController.hostingLease.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/EconomyReadOnlyPreviewController.java:112`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PreviewResult<Object>> hostingLease(@PathVariable String agentId, Authentication authentication, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /economy/preview/bounty-estimates`

- operationId：`EconomyReadOnlyPreviewController.bountyEstimate.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/EconomyReadOnlyPreviewController.java:121`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PreviewResult<Object>> bountyEstimate(@RequestBody byte[] body, Authentication authentication, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/hall/items/{sourceType}/{sourceId}/mark`

- operationId：`HallPrivateMarkController.get.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/HallPrivateMarkController.java:19`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<HallPrivateMarkService.View> get(@PathVariable String sourceType,@PathVariable String sourceId, HttpServletRequest request,Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `PATCH /agent/hall/items/{sourceType}/{sourceId}/mark`

- operationId：`HallPrivateMarkController.mark.PATCH`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/HallPrivateMarkController.java:25`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<HallPrivateMarkService.View> mark(@PathVariable String sourceType,@PathVariable String sourceId, @RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request,Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/hall/overview`

- operationId：`HallReadController.overview.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/HallReadController.java:24`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<HallReadService.Overview> overview(HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/hall/items`

- operationId：`HallReadController.items.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/HallReadController.java:32`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<HallReadService.Items> items(@RequestParam(required = false) String kind, @RequestParam(required = false) String view, @RequestParam(required = false) String q, @RequestParam(required = false) String cursor, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/hall/drafts`

- operationId：`HallRequestDraftController.create.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/HallRequestDraftController.java:54`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<HallRequestDraftService.DraftView> create( @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/hall/drafts/{draftId}`

- operationId：`HallRequestDraftController.get.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/HallRequestDraftController.java:67`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<HallRequestDraftService.DraftView> get(@PathVariable String draftId, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/hall/drafts`

- operationId：`HallRequestDraftController.list.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/HallRequestDraftController.java:74`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<HallRequestDraftService.DraftPage> list( @RequestParam(required = false) String cursor, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `PUT /agent/hall/drafts/{draftId}`

- operationId：`HallRequestDraftController.replace.PUT`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/HallRequestDraftController.java:82`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<HallRequestDraftService.DraftView> replace(@PathVariable String draftId, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/hall/drafts/{draftId}/discard`

- operationId：`HallRequestDraftController.discard.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/HallRequestDraftController.java:92`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<HallRequestDraftService.DraftView> discard(@PathVariable String draftId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/hall/drafts/{draftId}/submit`

- operationId：`HallRequestDraftController.submit.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/HallRequestDraftController.java:104`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<HallRequestDraftService.SubmissionReceipt> submit( @PathVariable String draftId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/hall/submissions/request`

- operationId：`HallSubmissionController.submission.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/HallSubmissionController.java:29`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<HallRequestDraftService.SubmissionReceipt> submission( @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/hall/cases/{caseId}`

- operationId：`HallSubmissionController.caseView.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/HallSubmissionController.java:38`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<HallRequestDraftService.CaseView> caseView(@PathVariable String caseId, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/hall/executions/{executionId}/results`

- operationId：`HallSubmissionController.executionResults.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/HallSubmissionController.java:46`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<HallRequestDraftService.ExecutionResultsView> executionResults( @PathVariable String executionId, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/personal-workspace/files`

- operationId：`PersonalWorkspaceController.list.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceController.java:54`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceViews.ListView> list( @RequestParam(required = false) String q, @RequestParam(required = false) String mediaFamily, @RequestParam(required = false) String state, @RequestParam(required = false) String cursor, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/personal-workspace/files`

- operationId：`PersonalWorkspaceController.create.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceController.java:61`
- 标记：MULTIPART
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceViews.UploadView> create(@RequestParam("file") MultipartFile file, @RequestParam(required = false) String displayName, @RequestHeader("Idempotency-Key") String key, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/personal-workspace/files/{fileId}`

- operationId：`PersonalWorkspaceController.get.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceController.java:68`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceViews.FileDetailView> get(@PathVariable String fileId, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `PATCH /agent/personal-workspace/files/{fileId}`

- operationId：`PersonalWorkspaceController.rename.PATCH`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceController.java:73`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceViews.FileView> rename(@PathVariable String fileId, @RequestBody RenameRequest body, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, @RequestHeader("Idempotency-Key") String key, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/personal-workspace/files/{fileId}/versions`

- operationId：`PersonalWorkspaceController.versions.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceController.java:80`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<Map<String, Object>> versions(@PathVariable String fileId, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/personal-workspace/files/{fileId}/versions`

- operationId：`PersonalWorkspaceController.append.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceController.java:84`
- 标记：MULTIPART
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceViews.UploadView> append(@PathVariable String fileId, @RequestParam("file") MultipartFile file, @RequestParam(required = false) String displayName, @RequestParam("expectedPreviousVersion") String expectedPreviousVersion, @RequestHeader("Idempotency-Key") String key, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/personal-workspace/files/{fileId}/versions/{version}/content`

- operationId：`PersonalWorkspaceController.content.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceController.java:93`
- 标记：BYTE_RESPONSE_REQUIRES_MEDIA_CHECK
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<byte[]> content(@PathVariable String fileId, @PathVariable String version, Authentication authentication)
```

- 目标封装规则：返回byte[]不等于二进制文件：先核实际Content-Type/序列化。若为自有application/json仍必须统一信封；真实文件才走字节例外。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/personal-workspace/files/{fileId}/versions/{version}/preview`

- operationId：`PersonalWorkspaceController.preview.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceController.java:97`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceViews.PreviewView> preview(@PathVariable String fileId, @PathVariable String version, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/personal-workspace/files/{fileId}/versions/{version}/preview/parts/{partId}`

- operationId：`PersonalWorkspaceController.previewPart.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceController.java:107`
- 标记：BYTE_RESPONSE_REQUIRES_MEDIA_CHECK
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<byte[]> previewPart(@PathVariable String fileId, @PathVariable String version, @PathVariable String partId, Authentication authentication)
```

- 目标封装规则：返回byte[]不等于二进制文件：先核实际Content-Type/序列化。若为自有application/json仍必须统一信封；真实文件才走字节例外。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/personal-workspace/files/{fileId}/usage`

- operationId：`PersonalWorkspaceController.usage.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceController.java:112`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceViews.UsageView> usage(@PathVariable String fileId, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/personal-workspace/files/{fileId}/trash`

- operationId：`PersonalWorkspaceController.trash.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceController.java:116`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceViews.FileView> trash(@PathVariable String fileId, @RequestBody TrashRequest body, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, @RequestHeader("Idempotency-Key") String key, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/personal-workspace/files/{fileId}/restore`

- operationId：`PersonalWorkspaceController.restore.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceController.java:123`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceViews.FileView> restore(@PathVariable String fileId, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, @RequestHeader("Idempotency-Key") String key, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/personal-workspace/operations/{operationId}`

- operationId：`PersonalWorkspaceController.operation.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceController.java:130`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceViews.OperationView> operation(@PathVariable String operationId, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/conversations/{conversationId}/file-links`

- operationId：`PersonalWorkspaceConversationFileLinkController.list.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceConversationFileLinkController.java:41`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceConversationLinkService.LinkListView> list( @PathVariable String conversationId, @RequestParam(required = false) String cursor, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/conversations/{conversationId}/file-links`

- operationId：`PersonalWorkspaceConversationFileLinkController.create.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceConversationFileLinkController.java:49`
- 标记：RAW_OR_DYNAMIC_BODY
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceConversationLinkService.LinkView> create( @PathVariable String conversationId, @RequestBody(required = false) Map<String, Object> body, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `DELETE /agent/conversations/{conversationId}/file-links/{relationId}`

- operationId：`PersonalWorkspaceConversationFileLinkController.detach.DELETE`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceConversationFileLinkController.java:65`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceConversationLinkService.DetachView> detach( @PathVariable String conversationId, @PathVariable String relationId, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /internal/agent/tasks/conversation-executions/commands`

- operationId：`PersonalWorkspaceConversationRuntimeController.commands.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceConversationRuntimeController.java:65`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<ConversationQueue> commands(HttpServletRequest request,Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /internal/agent/tasks/conversation-executions/controlled-image-v3-commands`

- operationId：`PersonalWorkspaceConversationRuntimeController.controlledV3Commands.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceConversationRuntimeController.java:72`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<ConversationQueueV3> controlledV3Commands(HttpServletRequest request,Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/lease`

- operationId：`PersonalWorkspaceConversationRuntimeController.claim.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceConversationRuntimeController.java:79`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceExecutionService.ConversationLease> claim(@PathVariable String taskId, @PathVariable String runId,@RequestBody ClaimRequest claim,HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/lease/renew`

- operationId：`PersonalWorkspaceConversationRuntimeController.renew.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceConversationRuntimeController.java:90`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceExecutionService.ConversationLease> renew(@PathVariable String taskId, @PathVariable String runId,@RequestBody FenceRequest request,HttpServletRequest servletRequest, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/inputs`

- operationId：`PersonalWorkspaceConversationRuntimeController.inputs.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceConversationRuntimeController.java:147`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<cn.jia.agent.service.SkillExecutionService.Inputs> inputs( @PathVariable String taskId,@PathVariable String runId,@RequestBody FenceRequest fence, HttpServletRequest request,Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/inputs/{inputRef}/content`

- operationId：`PersonalWorkspaceConversationRuntimeController.inputContent.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceConversationRuntimeController.java:157`
- 标记：BYTE_RESPONSE_REQUIRES_MEDIA_CHECK
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<byte[]> inputContent(@PathVariable String taskId,@PathVariable String runId, @PathVariable String inputRef,@RequestBody FenceRequest fence, HttpServletRequest request,Authentication authentication)
```

- 目标封装规则：返回byte[]不等于二进制文件：先核实际Content-Type/序列化。若为自有application/json仍必须统一信封；真实文件才走字节例外。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/outputs/{outputId}/content`

- operationId：`PersonalWorkspaceConversationRuntimeController.stage.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceConversationRuntimeController.java:173`
- 标记：MULTIPART
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceExecutionService.StagedOutput> stage(@PathVariable String taskId, @PathVariable String runId,@PathVariable String outputId,@RequestParam("file") MultipartFile file, @RequestParam("version") String version,@RequestParam("token") String token, @RequestParam("sha256") String sha256,@RequestParam("length") String length, HttpServletRequest request,Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/output-commits/{manifestId}`

- operationId：`PersonalWorkspaceConversationRuntimeController.commit.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceConversationRuntimeController.java:191`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceExecutionService.CommitView> commit(@PathVariable String taskId, @PathVariable String runId,@PathVariable String manifestId,@RequestBody CommitRequest body, HttpServletRequest request,Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/effects/{effectId}/start`

- operationId：`PersonalWorkspaceConversationRuntimeController.skillEffectStart.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceConversationRuntimeController.java:278`
- 标记：RAW_OR_DYNAMIC_BODY
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<cn.jia.agent.service.SkillExecutionService.EffectReceipt> skillEffectStart( @PathVariable String taskId,@PathVariable String runId,@PathVariable String effectId,@RequestBody String raw,HttpServletRequest request,Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/effects/{effectId}/result`

- operationId：`PersonalWorkspaceConversationRuntimeController.skillEffectResult.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceConversationRuntimeController.java:284`
- 标记：RAW_OR_DYNAMIC_BODY
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<cn.jia.agent.service.SkillExecutionService.EffectReceipt> skillEffectResult( @PathVariable String taskId,@PathVariable String runId,@PathVariable String effectId,@RequestBody String raw,HttpServletRequest request,Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/resume`

- operationId：`PersonalWorkspaceConversationRuntimeController.skillResume.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceConversationRuntimeController.java:290`
- 标记：RAW_OR_DYNAMIC_BODY
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<cn.jia.agent.service.SkillExecutionService.ResumeReceipt> skillResume( @PathVariable String taskId,@PathVariable String runId,@RequestBody String raw,HttpServletRequest request,Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/result-commits/{manifestId}`

- operationId：`PersonalWorkspaceConversationRuntimeController.skillResultCommit.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceConversationRuntimeController.java:296`
- 标记：RAW_OR_DYNAMIC_BODY
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<cn.jia.agent.service.SkillExecutionService.ResultReceipt> skillResultCommit( @PathVariable String taskId,@PathVariable String runId,@PathVariable String manifestId,@RequestBody String raw,HttpServletRequest request,Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /internal/agent/tasks/{taskId}/runs/{runId}/conversation/failure`

- operationId：`PersonalWorkspaceConversationRuntimeController.failure.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceConversationRuntimeController.java:303`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceExecutionService.ExecutionView> failure(@PathVariable String taskId, @PathVariable String runId,@RequestBody FailRequest body,HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/personal-workspace/executions/capabilities`

- operationId：`PersonalWorkspaceExecutionController.capabilities.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceExecutionController.java:36`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceExecutionService.ExecutionCapabilities> capabilities(Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/personal-workspace/executions`

- operationId：`PersonalWorkspaceExecutionController.create.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceExecutionController.java:42`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceExecutionService.ExecutionView> create( @RequestBody CreateRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/personal-workspace/executions`

- operationId：`PersonalWorkspaceExecutionController.list.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceExecutionController.java:56`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceExecutionService.ExecutionHistoryView> list( @RequestParam(required = false) String limit, @RequestParam(required = false) String beforeCreatedAt, @RequestParam(required = false) String beforeExecutionId, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/personal-workspace/executions/request`

- operationId：`PersonalWorkspaceExecutionController.request.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceExecutionController.java:69`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceExecutionService.ExecutionView> request( @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/personal-workspace/executions/{executionId}`

- operationId：`PersonalWorkspaceExecutionController.get.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceExecutionController.java:77`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceExecutionService.ExecutionView> get(@PathVariable String executionId, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/personal-workspace/executions/{executionId}/revoke-inputs`

- operationId：`PersonalWorkspaceExecutionController.revoke.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceExecutionController.java:83`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceExecutionService.ExecutionView> revoke(@PathVariable String executionId, @RequestBody RevokeRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /internal/agent/tasks/workspace-executions/commands`

- operationId：`PersonalWorkspaceRuntimeFileController.queuedCommands.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceRuntimeFileController.java:41`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<RuntimeQueueView> queuedCommands(HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /internal/agent/tasks/{taskId}/runs/{runId}/start`

- operationId：`PersonalWorkspaceRuntimeFileController.start.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceRuntimeFileController.java:48`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceExecutionService.RuntimeStartView> start(@PathVariable String taskId, @PathVariable String runId, @RequestBody StartRequest request, HttpServletRequest servletRequest, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /internal/agent/tasks/{taskId}/runs/{runId}/inputs`

- operationId：`PersonalWorkspaceRuntimeFileController.inputs.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceRuntimeFileController.java:59`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<List<PersonalWorkspaceExecutionService.RuntimeInput>> inputs(@PathVariable String taskId, @PathVariable String runId, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /internal/agent/tasks/{taskId}/runs/{runId}/inputs/{inputRef}/content`

- operationId：`PersonalWorkspaceRuntimeFileController.content.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceRuntimeFileController.java:66`
- 标记：BYTE_RESPONSE_REQUIRES_MEDIA_CHECK
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<byte[]> content(@PathVariable String taskId, @PathVariable String runId, @PathVariable String inputRef, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：返回byte[]不等于二进制文件：先核实际Content-Type/序列化。若为自有application/json仍必须统一信封；真实文件才走字节例外。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /internal/agent/tasks/{taskId}/runs/{runId}/outputs/{outputId}/content`

- operationId：`PersonalWorkspaceRuntimeFileController.stage.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceRuntimeFileController.java:77`
- 标记：MULTIPART
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceExecutionService.StagedOutput> stage(@PathVariable String taskId, @PathVariable String runId, @PathVariable String outputId, @RequestParam("file") MultipartFile file, @RequestParam("taskId") String declaredTaskId, @RequestParam("runId") String declaredRunId, @RequestParam("outputId") String declaredOutputId, @RequestParam("sha256") String declaredSha256, @RequestParam("length") String declaredLength, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /internal/agent/tasks/{taskId}/runs/{runId}/output-commits/{manifestId}`

- operationId：`PersonalWorkspaceRuntimeFileController.commit.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceRuntimeFileController.java:95`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceExecutionService.CommitView> commit(@PathVariable String taskId, @PathVariable String runId, @PathVariable String manifestId, @RequestBody CommitRequest request, HttpServletRequest servletRequest, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /internal/agent/tasks/{taskId}/runs/{runId}/failure`

- operationId：`PersonalWorkspaceRuntimeFileController.failure.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceRuntimeFileController.java:105`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceExecutionService.ExecutionView> failure(@PathVariable String taskId, @PathVariable String runId, @RequestBody FailureRequest request, HttpServletRequest servletRequest, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/{taskId}/file-links`

- operationId：`PersonalWorkspaceTaskFileLinkController.list.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceTaskFileLinkController.java:41`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceTaskLinkService.LinkListView> list( @PathVariable String taskId, @RequestParam(required = false) String cursor, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/file-links`

- operationId：`PersonalWorkspaceTaskFileLinkController.create.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceTaskFileLinkController.java:49`
- 标记：RAW_OR_DYNAMIC_BODY
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceTaskLinkService.LinkView> create( @PathVariable String taskId, @RequestBody(required = false) Map<String, Object> body, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `DELETE /agent/tasks/{taskId}/file-links/{relationId}`

- operationId：`PersonalWorkspaceTaskFileLinkController.detach.DELETE`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceTaskFileLinkController.java:65`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<PersonalWorkspaceTaskLinkService.DetachView> detach( @PathVariable String taskId, @PathVariable String relationId, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/{agentId}/skill-install-credentials`

- operationId：`SkillManagedCredentialController.rotate.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/SkillManagedCredentialController.java:16`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object rotate(@PathVariable String agentId,Authentication auth,HttpServletRequest request,@RequestBody byte[] raw)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /economy/capabilities`

- operationId：`SkillMarketplaceController.capabilities.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/SkillMarketplaceController.java:16`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object capabilities(Authentication auth,HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/skill-products`

- operationId：`SkillMarketplaceController.catalog.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/SkillMarketplaceController.java:20`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object catalog(Authentication auth,HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/skill-products/{productId}`

- operationId：`SkillMarketplaceController.product.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/SkillMarketplaceController.java:24`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object product(@PathVariable String productId,Authentication auth,HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/skill-orders/quotes`

- operationId：`SkillMarketplaceController.quote.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/SkillMarketplaceController.java:28`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object quote(Authentication auth,HttpServletRequest request,@RequestBody byte[] body)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/skill-orders`

- operationId：`SkillMarketplaceController.purchase.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/SkillMarketplaceController.java:32`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object purchase(Authentication auth,HttpServletRequest request,@RequestBody byte[] body)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/skill-orders/{orderId}`

- operationId：`SkillMarketplaceController.order.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/SkillMarketplaceController.java:36`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object order(@PathVariable String orderId,Authentication auth,HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/{agentId}/skill-entitlements`

- operationId：`SkillMarketplaceController.entitlements.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/SkillMarketplaceController.java:40`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object entitlements(@PathVariable String agentId,Authentication auth,HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /internal/agent/skill-installations/{installationId}/package`

- operationId：`SkillPackageController.download.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/SkillPackageController.java:15`
- 标记：BYTE_RESPONSE_REQUIRES_MEDIA_CHECK
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<byte[]> download(@PathVariable String installationId,Authentication auth,HttpServletRequest request)
```

- 目标封装规则：返回byte[]不等于二进制文件：先核实际Content-Type/序列化。若为自有application/json仍必须统一信封；真实文件才走字节例外。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/skill-maintenance/product-versions`

- operationId：`SkillPackagePublicationController.publish.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/SkillPackagePublicationController.java:25`
- 标记：MULTIPART, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<?> publish(Authentication authentication,HttpServletRequest request,@RequestPart("metadata") String metadata,@RequestPart("package") MultipartFile archive)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/{taskId}/settlement`

- operationId：`AgentBountySettlementController.read.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/funding/AgentBountySettlementController.java:25`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<?> read(@PathVariable String taskId, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/funding/complete`

- operationId：`AgentBountySettlementController.complete.POST`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/api/funding/AgentBountySettlementController.java:32`
- 标记：RAW_OR_DYNAMIC_BODY, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<?> complete(@PathVariable String taskId, @RequestBody Map<String, Object> body, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/conversations/{conversationId}/deliverables`

- operationId：`ConversationDeliverableController.list.GET`
- 源码：`agent/jia-agent-service/src/main/java/cn/jia/agent/conversation/ConversationDeliverableController.java:37`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<ConversationDeliverableReadAdapter.Page> list( @PathVariable String conversationId, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。


### 聚义厅事项阶段读模型（2026-10-19 实施候选，未上线）

本节沿用统一接口规范 v1.2；源码实现与部署状态分开。`POST /agent/tasks/search` 的业务 data 新增可选 `phase`：`PENDING_ASSIGNMENT|IN_PROGRESS|PENDING_ACCEPTANCE|COMPLETED|HISTORY|UNKNOWN`，不能与非 null `status` 同传，非法值/冲突返回400。`status`仅保留原始状态诊断用途。分页不变；每项新增 `phase`，原始 `status`不变。按ID读取的原详情DTO允许 `phase=null`，不把它当已核对的验收权限。

列表、分页总数及 `POST /agent/tasks/status-counts` 共用固定 tenant、认证 client/owner、ability及完整 keyword（任务ID、标题、描述、本领、承办名称）谓词。阶段只读派生，不新增root状态：open→待点将；planning/assigned/running/blocked→进行中；completed→已完成；failed/cancelled/archived→历史。reviewing必须恰有一个当前submitted正式交付且不存在更新revision，否则UNKNOWN；非终态却残留submitted也UNKNOWN。终态根事实优先。此阶段不是可写权限，ACCEPT仍必须核对完整review-context、版本、来源及幂等操作。

计数成功业务data固定为 `{phaseCounts,rawStatusCounts}`；两者均有 `total`，所有计数为非负规范十进制 **string**（可大于JS安全整数），没有当前页兜底。phaseCounts固定六个阶段键；rawStatusCounts含原始状态，未知/null持久状态不伪造open。计数忽略当前选中阶段，scope/ability/keyword与列表相同；失败不是0。旧裸数值map不再作为新UI协议。该候选不得单独发布到仍消费旧计数结构的Web。


#### 概览TASK阶段同步（2026-10-19候选，未上线）

核对源码 `77de508b6a75e1ecb49bd5eda3a369558ae83aac`，覆盖 `HallReadSql` / `HallReadService.ItemSummary` / `HallReadWire` 以及 Web `8ad9be0098e1f7d848ade2f610270448d02116fe` 的概览消费者。`GET /agent/hall/overview` 和 `GET /agent/hall/items` 的TASK条目新增显式 `phase`，取值与上节六阶段相同；其他来源返回null。来源仍为互相独立的keyset partition，不新增全局统计。

TASK概览和事项分页/阶段计数复用同一 `AgentTaskPhaseSql.PHASE`。`review` 仅在 `phase=PENDING_ACCEPTANCE` 且真实reviewReady完整时返回；多个submitted、最新交付矛盾等即使latest-only join取到一个批次，也必须UNKNOWN且review=null。正式schema关闭时不访问正式交付表，根终态保留COMPLETED/HISTORY，其余UNKNOWN。

此字段仅为只读展示，不是验收授权。Web对缺失/非法/矛盾phase隔离TASK来源错误；不把reviewing自行提升为可验收，UNKNOWN只读核对。真实MyBatis/MySQL逐项对照及正式Flow180通过，线上部署未发生，部署后的身份/ACL及真实页面核验不由该证据替代。

## 本领本人维护合同（2026-10-29，接口标准 v1.4）

本节覆盖旧注册/状态接口中资料和本领的可写声明，其他接口条目仍保留原基线。实施基线 de6776fb3092a3e71aa5004d115c1280b742f2aa，发布状态另见根项目 handoff JUYITING-PERSONA-ABILITIES-20261029。

- `PUT /agent/{agentId}/abilities`，JWT 本人已绑定的非系统好汉；公共 `JsonRequest.data` 为 `{expectedAbilities: string[], abilities: string[]}`，两项必须给出，允许清空 `[]`。成功 `200`、公共 JsonResult.data 为权威 AgentRuntimeDTO，所有响应 `Cache-Control: private, no-store`。
- 服务端先归一化新值，事务内沿已有身份/绑定/运行行锁检查租户、客户端、所有者及有效绑定；当前投影本领须与 expectedAbilities 按项/顺序相等，不同返回 `409 / AGENT_ABILITIES_CONFLICT`，零写入。此为最终值 CAS，避免覆盖不同的当前值，不识别 ABA/编辑历史；介绍文本不新增修订历史。保存仅改本领，不改任务、状态、错误、心跳、技能就绪或授权。提交后沿现有身份隔离发布最新快照。
- 新值最多128项、每项 strip 后1–100个 UTF-16 单元，拒绝 null/空项/ISO控制字符；按 Locale.ROOT 小写键保留首项去重。沿用原本领字段/匹配输入界限，防止介绍载荷无界扩展、标签/匹配控制字符混入；非法入参400/BAD_REQUEST，越权403/AGENT_FORBIDDEN，数据库写失败500/AGENT_ERROR。失败不改本领；未增加注册或运行就绪门禁。
- Web 每行一项，移除空行和重复项，空列表可保存；取消不写。保存后读取 `profile-capabilities`，只有权威读回匹配才展示保存完成；409或网络未知保留草稿，显式核对最新值后再决定保存，不自动重试。切换身份/好汉/面板中止并隔离旧回调；非本人/宋江只读。本领仍参与现有任务匹配，不是已安装技能、就绪或执行授权证明。
- 请上梁山创建新 runtime 时初始化资料/默认本领，重复绑定不覆盖。注册 DTO 只含 agentId/endpoint，状态 DTO 只含 status/currentTaskId/currentTaskTitle/errorMessage；两条链路均不能覆盖资料或本领。不提供旧客户端双轨适配。WS 身份/会话、防重及实际消费的运行声明保留：runtimeCapabilities、typedDeliberation（含材料输入和nativeExecution）、skillExecution；presence 仅动态状态和必要 fence。
- 回归：WaterMarginPersonaCatalogTest、AgentServiceImplTest、AgentControllerTest、AgentWebSocketHandlerTest、AgentRuntimeCapabilitiesTest；Web juyiting-agent-abilities-editor.test.js；Runtime agent-client.test.mjs、registration-footprint.test.mjs。正式构建/线上验收证据独立记录，不以本节更新代替已上线。
