# chat：逐HTTP接口源码清单

基线 `7e2d07a05b9e9b18461e97abcff44642504320ff`；70 个method/path条目。源代码声明不是目标契约已完成。公共封装/权限/幂等见[目录说明](README.md)，人工复核关键链见[逐接口契约](critical-contracts.md)。

## `GET /agent/active`

- operationId：`ActiveAgentController.activeAgents.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ActiveAgentController.java:22`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object activeAgents()
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /internal/agent/chat/requests/{requestId}/turns/{turnId}/inspection/inputs/{sourceRefId}/content`

- operationId：`AgentInspectionInputController.content.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/AgentInspectionInputController.java:37`
- 标记：BYTE_RESPONSE_REQUIRES_MEDIA_CHECK
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<byte[]> content(@PathVariable String requestId, @PathVariable String turnId, @PathVariable String sourceRefId, Authentication authentication, HttpServletRequest request)
```

- 目标封装规则：返回byte[]不等于二进制文件：先核实际Content-Type/序列化。若为自有application/json仍必须统一信封；真实文件才走字节例外。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /internal/agent/chat/requests/{requestId}/turns/{turnId}/materials/inputs/{sourceRefId}/content`

- operationId：`AgentMaterialsInputController.content.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/AgentMaterialsInputController.java:37`
- 标记：BYTE_RESPONSE_REQUIRES_MEDIA_CHECK
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<byte[]> content(@PathVariable String requestId, @PathVariable String turnId, @PathVariable String sourceRefId, Authentication authentication, HttpServletRequest request)
```

- 目标封装规则：返回byte[]不等于二进制文件：先核实际Content-Type/序列化。若为自有application/json仍必须统一信封；真实文件才走字节例外。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/{taskId}/point-and-start-controlled-image-capability`

- operationId：`AgentTaskControlledImageCapabilityController.read.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/AgentTaskControlledImageCapabilityController.java:36`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<ControlledImagePointAndStartCapabilityService.Capability>> read( @PathVariable String taskId,@RequestParam(name="targetAgentId",required=false)String targetAgentId, HttpServletRequest request,Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/{taskId}/point-and-start-capability`

- operationId：`AgentTaskPointAndStartCapabilityController.read.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/AgentTaskPointAndStartCapabilityController.java:42`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<PointAndStartCapabilityService.Capability>> read( @PathVariable String taskId, @RequestParam(name = "targetAgentId", required = false) String targetAgentId, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /agent/tasks/{taskId}/finalizations`

- operationId：`AgentTaskSelectedOutputFinalizationController.submit.POST`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/AgentTaskSelectedOutputFinalizationController.java:63`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<ChatSelectedOutputFinalizationService.Receipt>> submit(@PathVariable String taskId, @RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest request,Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/{taskId}/finalizations/request`

- operationId：`AgentTaskSelectedOutputFinalizationController.byRequest.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/AgentTaskSelectedOutputFinalizationController.java:73`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<ChatSelectedOutputFinalizationService.Receipt>> byRequest(@PathVariable String taskId, @RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest request,Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/{taskId}/finalizations/{operationId}`

- operationId：`AgentTaskSelectedOutputFinalizationController.byOperation.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/AgentTaskSelectedOutputFinalizationController.java:80`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<ChatSelectedOutputFinalizationService.Receipt>> byOperation(@PathVariable String taskId, @PathVariable String operationId,HttpServletRequest request,Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /chat/task-threads/{taskId}/team`

- operationId：`AgentTaskThreadController.getOrCreateTeamThread.POST`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/AgentTaskThreadController.java:28`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object getOrCreateTeamThread( @PathVariable String taskId, @RequestBody(required = false) AgentTaskThreadCreateDTO request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /chat/task-threads/{taskId}/team`

- operationId：`AgentTaskThreadController.getTeamThread.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/AgentTaskThreadController.java:39`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object getTeamThread( @PathVariable String taskId, @RequestParam String actorAgentId)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /chat/task-threads/{taskId}/team/messages`

- operationId：`AgentTaskThreadController.appendTeamMessage.POST`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/AgentTaskThreadController.java:48`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object appendTeamMessage( @PathVariable String taskId, @RequestBody(required = false) AgentTaskThreadMessageCreateDTO request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /chat/task-threads/{taskId}/team/messages`

- operationId：`AgentTaskThreadController.listTeamMessages.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/AgentTaskThreadController.java:57`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object listTeamMessages( @PathVariable String taskId, @RequestParam String actorAgentId, @RequestParam(defaultValue = "100") int limit)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /chat/conversations/{conversationId}/requests/{requestId}/abandon-execution`

- operationId：`ChatBountyExecutionTerminationController.abandon.POST`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatBountyExecutionTerminationController.java:47`
- 标记：RAW_OR_DYNAMIC_BODY
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<Receipt>> abandon(@PathVariable String conversationId, @PathVariable String requestId,@RequestHeader("Idempotency-Key") String key, @RequestBody(required=false) String body,Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /chat/conversations/{conversationId}/requests/{requestId}/abandon-execution`

- operationId：`ChatBountyExecutionTerminationController.outcome.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatBountyExecutionTerminationController.java:55`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<Receipt>> outcome(@PathVariable String conversationId, @PathVariable String requestId,@RequestHeader("Idempotency-Key") String key, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /chat/conversations/{conversationId}/interactions`

- operationId：`ChatBountyInteractionController.interact.POST`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatBountyInteractionController.java:60`
- 标记：RETIRED_410, RAW_OR_DYNAMIC_BODY
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<Void>> interact( @PathVariable String conversationId, @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody(required=false) String raw, Authentication authentication, HttpServletRequest servletRequest)
```

- 目标封装规则：退役写入口：固定410，data=null；不得复活或映射成新技能执行。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /chat/conversations/{conversationId}/interactions/context`

- operationId：`ChatBountyInteractionV3Controller.context.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatBountyInteractionV3Controller.java:44`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<ChatBountyInteractionV3Wire.ContextData>> context( @PathVariable String conversationId,Authentication authentication,HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /chat/conversations/{conversationId}/interactions/preview`

- operationId：`ChatBountyInteractionV3Controller.preview.POST`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatBountyInteractionV3Controller.java:50`
- 标记：RETIRED_410, RAW_OR_DYNAMIC_BODY
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<ChatBountyInteractionV3Wire.PreviewData>> preview( @PathVariable String conversationId,@RequestHeader("Idempotency-Key") String interactionKey, @RequestBody(required=false) String raw,Authentication authentication,HttpServletRequest request)
```

- 目标封装规则：退役写入口：固定410，data=null；不得复活或映射成新技能执行。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /chat/conversations/{conversationId}/interactions/provider-consents`

- operationId：`ChatBountyInteractionV3Controller.issue.POST`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatBountyInteractionV3Controller.java:57`
- 标记：RETIRED_410, RAW_OR_DYNAMIC_BODY
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<cn.jia.agent.entity.ControlledImageFollowupAuthorityDTO>> issue( @PathVariable String conversationId,@RequestHeader("Idempotency-Key") String issueKey, @RequestBody(required=false) String raw,Authentication authentication,HttpServletRequest request)
```

- 目标封装规则：退役写入口：固定410，data=null；不得复活或映射成新技能执行。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /chat/conversations/{conversationId}/interactions/provider-consents/request`

- operationId：`ChatBountyInteractionV3Controller.getIssue.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatBountyInteractionV3Controller.java:64`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<cn.jia.agent.entity.ControlledImageFollowupAuthorityDTO>> getIssue( @PathVariable String conversationId,@RequestHeader("Idempotency-Key") String issueKey, Authentication authentication,HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /chat/conversations/{conversationId}/interactions/provider-consents/{consentId}/revoke`

- operationId：`ChatBountyInteractionV3Controller.revoke.POST`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatBountyInteractionV3Controller.java:71`
- 标记：RETIRED_410, RAW_OR_DYNAMIC_BODY
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<cn.jia.agent.entity.ControlledImageFollowupAuthorityDTO>> revoke( @PathVariable String conversationId,@PathVariable String consentId, @RequestHeader("Idempotency-Key") String revokeKey,@RequestBody(required=false) String raw, Authentication authentication,HttpServletRequest request)
```

- 目标封装规则：退役写入口：固定410，data=null；不得复活或映射成新技能执行。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /chat/conversations/{conversationId}/interactions/request`

- operationId：`ChatBountyInteractionV3Controller.getInteraction.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatBountyInteractionV3Controller.java:79`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<ChatBountyInteractionV3Wire.AcceptedData>> getInteraction( @PathVariable String conversationId,@RequestHeader("Idempotency-Key") String interactionKey, Authentication authentication,HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /chat/requests/{requestId}/steps/{stepId}/outputs`

- operationId：`ChatBountyMediaController.outputs.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatBountyMediaController.java:103`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<List<OutputItem>>> outputs(@PathVariable String requestId, @PathVariable String stepId, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /chat/requests/{requestId}/steps/{stepId}/outputs/{outputId}`

- operationId：`ChatBountyMediaController.content.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatBountyMediaController.java:172`
- 标记：BYTE_RESPONSE_REQUIRES_MEDIA_CHECK
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<byte[]> content(@PathVariable String requestId, @PathVariable String stepId, @PathVariable String outputId, @RequestParam(defaultValue = "false") boolean download, @RequestHeader(value = HttpHeaders.RANGE, required = false) String range, @RequestHeader(value = HttpHeaders.IF_RANGE, required = false) String ifRange, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：返回byte[]不等于二进制文件：先核实际Content-Type/序列化。若为自有application/json仍必须统一信封；真实文件才走字节例外。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `HEAD /chat/requests/{requestId}/steps/{stepId}/outputs/{outputId}`

- operationId：`ChatBountyMediaController.content.HEAD`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatBountyMediaController.java:172`
- 标记：BYTE_RESPONSE_REQUIRES_MEDIA_CHECK
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<byte[]> content(@PathVariable String requestId, @PathVariable String stepId, @PathVariable String outputId, @RequestParam(defaultValue = "false") boolean download, @RequestHeader(value = HttpHeaders.RANGE, required = false) String range, @RequestHeader(value = HttpHeaders.IF_RANGE, required = false) String ifRange, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：返回byte[]不等于二进制文件：先核实际Content-Type/序列化。若为自有application/json仍必须统一信封；真实文件才走字节例外。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /chat/conversations/{conversationId}/requests`

- operationId：`ChatBountyRequestIndexController.requests.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatBountyRequestIndexController.java:48`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<ChatBountyRequestIndexService.Page>> requests( @PathVariable String conversationId, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /chat/stream`

- operationId：`ChatController.handleChat.POST`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:166`
- 标记：STREAM
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Flux<String> handleChat(@RequestBody ChatMessageDTO chatMessage, @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey)
```

- 目标封装规则：显式字节/流契约；禁止JSON包装已开始传输的内容。错误、HEAD/Range/断流按专属契约定义。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /chat/capabilities`

- operationId：`ChatController.chatCapabilities.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:503`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object chatCapabilities()
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /chat/requests/{requestId}`

- operationId：`ChatController.getChatRequest.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:522`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object getChatRequest(@org.springframework.web.bind.annotation.PathVariable String requestId, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /chat/turns/{turnId}`

- operationId：`ChatController.getChatTurn.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:530`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object getChatTurn(@org.springframework.web.bind.annotation.PathVariable String turnId, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /chat/turns/{turnId}/cancel`

- operationId：`ChatController.cancelChatTurn.POST`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:538`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object cancelChatTurn(@org.springframework.web.bind.annotation.PathVariable String turnId, @RequestBody(required = false) ChatCancelDTO cancellation, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /chat/requests/{requestId}/cancel`

- operationId：`ChatController.cancelPendingChatTurns.POST`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:548`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object cancelPendingChatTurns( @org.springframework.web.bind.annotation.PathVariable String requestId, @RequestParam(name = "allPending", required = false) Boolean allPending, @RequestBody(required = false) ChatCancelDTO cancellation, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /chat/stop_stream`

- operationId：`ChatController.stopStream.POST`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:578`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object stopStream(@RequestBody ChatMessageDTO chatMessage)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `DELETE /chat/conversation/delete`

- operationId：`ChatController.deleteConversation.DELETE`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:585`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object deleteConversation(@RequestParam(name = "id") String id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /chat/conversation/content`

- operationId：`ChatController.getConversationContent.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:591`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object getConversationContent(@RequestParam(name = "id") String id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /chat/conversation/events`

- operationId：`ChatController.conversationEvents.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:662`
- 标记：STREAM
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Flux<ServerSentEvent<String>> conversationEvents(@RequestParam(name = "id") String id, @RequestParam(name = "cursor", required = false) String cursor, @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId, Authentication authentication)
```

- 目标封装规则：显式字节/流契约；禁止JSON包装已开始传输的内容。错误、HEAD/Range/断流按专属契约定义。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /chat/conversation/list`

- operationId：`ChatController.listConversations.POST`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:813`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object listConversations(@RequestBody JsonRequestPage<ChatConversationEntity> page)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /chat/conversation/update`

- operationId：`ChatController.updateConversation.POST`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:829`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object updateConversation(@RequestBody ChatConversationEntity entity)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /chat/library/search`

- operationId：`ChatController.searchLibrary.POST`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:835`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object searchLibrary(@RequestBody LibrarySearchRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /chat/library/documents`

- operationId：`ChatController.saveLibraryDocument.POST`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:863`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object saveLibraryDocument(@RequestBody LibraryDocumentRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /chat/conversations/{conversationId}/archive-operations`

- operationId：`ChatConversationArchiveController.archive.POST`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatConversationArchiveController.java:55`
- 标记：RAW_OR_DYNAMIC_BODY
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<ChatConversationArchiveService.Receipt>> archive( @PathVariable String conversationId, @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody Map<String, Object> body, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /chat/conversations/{conversationId}/archive-operations/{operationId}`

- operationId：`ChatConversationArchiveController.status.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatConversationArchiveController.java:70`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<ChatConversationArchiveService.Receipt>> status( @PathVariable String conversationId, @PathVariable String operationId, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /chat/conversations/{conversationId}/assets/{assetId}`

- operationId：`ChatConversationAssetController.detail.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatConversationAssetController.java:51`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<AssetView>> detail(@PathVariable String conversationId, @PathVariable String assetId, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /chat/conversations/{conversationId}/assets/{assetId}/content`

- operationId：`ChatConversationAssetController.content.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatConversationAssetController.java:61`
- 标记：BYTE_RESPONSE_REQUIRES_MEDIA_CHECK
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<byte[]> content(@PathVariable String conversationId, @PathVariable String assetId, @RequestParam(defaultValue="false") boolean download, @RequestHeader(value=HttpHeaders.RANGE,required=false) String range, @RequestHeader(value=HttpHeaders.IF_RANGE,required=false) String ifRange, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：返回byte[]不等于二进制文件：先核实际Content-Type/序列化。若为自有application/json仍必须统一信封；真实文件才走字节例外。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `HEAD /chat/conversations/{conversationId}/assets/{assetId}/content`

- operationId：`ChatConversationAssetController.content.HEAD`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatConversationAssetController.java:61`
- 标记：BYTE_RESPONSE_REQUIRES_MEDIA_CHECK
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<byte[]> content(@PathVariable String conversationId, @PathVariable String assetId, @RequestParam(defaultValue="false") boolean download, @RequestHeader(value=HttpHeaders.RANGE,required=false) String range, @RequestHeader(value=HttpHeaders.IF_RANGE,required=false) String ifRange, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：返回byte[]不等于二进制文件：先核实际Content-Type/序列化。若为自有application/json仍必须统一信封；真实文件才走字节例外。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /chat/conversations/{conversationId}/executions/{executionId}/result`

- operationId：`ChatSkillResultController.result.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatSkillResultController.java:26`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<Map<String,Object>>> result(@PathVariable String conversationId,@PathVariable String executionId,Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /chat/conversations/{conversationId}/interactions/discussion`

- operationId：`ChatTypedDeliberationController.discuss.POST`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatTypedDeliberationController.java:47`
- 标记：RAW_OR_DYNAMIC_BODY
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<ChatTypedDeliberationWire.Accepted>> discuss( @PathVariable String conversationId,@RequestHeader("Idempotency-Key") String key, @RequestBody(required=false) String rawBody,Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /chat/conversations/{conversationId}/requests/{requestId}/typed-outcome`

- operationId：`ChatTypedDeliberationController.outcome.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatTypedDeliberationController.java:58`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<Object>> outcome( @PathVariable String conversationId,@PathVariable String requestId,Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /chat/conversations/{conversationId}/interactions/inspection`

- operationId：`ChatTypedInspectionController.admit.POST`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatTypedInspectionController.java:52`
- 标记：RETIRED_410, RAW_OR_DYNAMIC_BODY
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<ChatTypedInspectionWire.Accepted>> admit( @PathVariable String conversationId, @RequestBody(required = false) String raw, Authentication authentication, HttpServletRequest request)
```

- 目标封装规则：退役写入口：固定410，data=null；不得复活或映射成新技能执行。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /chat/conversations/{conversationId}/interactions/inspection/request`

- operationId：`ChatTypedInspectionController.recover.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatTypedInspectionController.java:59`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<ChatTypedInspectionWire.Accepted>> recover( @PathVariable String conversationId, Authentication authentication, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /chat/conversations/{conversationId}/requests/{requestId}/inspection-outcome`

- operationId：`ChatTypedInspectionController.outcome.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatTypedInspectionController.java:72`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<Object>> outcome( @PathVariable String conversationId, @PathVariable String requestId, Authentication authentication, HttpServletRequest servletRequest)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /juyiting/actions/{intentId}/dispatch`

- operationId：`JuyitingActionController.dispatch.POST`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/JuyitingActionController.java:27`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public JsonResult<HallActionDispatchResult> dispatch( @PathVariable String intentId, @RequestParam(required = false) String callerAgentId, @RequestBody HallActionIntent request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /juyiting/agents/{agentId}/mailbox`

- operationId：`JuyitingActionController.mailbox.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/JuyitingActionController.java:45`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public JsonResult<Object> mailbox( @PathVariable String agentId, @RequestParam(required = false) String callerAgentId, @RequestParam(required = false) String taskId, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "false") boolean includeTerminal, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /agent/tasks/{taskId}/skill-capabilities`

- operationId：`TaskSkillCapabilityController.read.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/TaskSkillCapabilityController.java:25`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<ChatActionCapabilityService.TaskCatalog>> read(@PathVariable String taskId, @RequestParam String targetAgentId,Authentication authentication,HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /archive/v1/catalog`

- operationId：`ArchiveController.catalog.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchiveController.java:34`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<?>> catalog( @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /archive/v1/editions/{editionId}/preface`

- operationId：`ArchiveController.preface.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchiveController.java:50`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<?>> preface( @PathVariable String editionId, @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /archive/v1/editions/{editionId}/chapters/{chapterId}`

- operationId：`ArchiveController.chapter.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchiveController.java:68`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<?>> chapter( @PathVariable String editionId, @PathVariable String chapterId, @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /archive/v1/me/progress/{editionId}`

- operationId：`ArchivePersonalDataController.progress.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchivePersonalDataController.java:46`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<?>> progress(@PathVariable String editionId, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `PUT /archive/v1/me/progress/{editionId}`

- operationId：`ArchivePersonalDataController.putProgress.PUT`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchivePersonalDataController.java:57`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<?> putProgress(@PathVariable String editionId, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody(required = false) byte[] body, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /archive/v1/me/bookmarks`

- operationId：`ArchivePersonalDataController.bookmarks.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchivePersonalDataController.java:72`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<?>> bookmarks(@RequestParam String editionId, @RequestParam(required = false) String cursor, @RequestParam(required = false) String limit, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `PUT /archive/v1/me/bookmarks/{bookmarkId}`

- operationId：`ArchivePersonalDataController.putBookmark.PUT`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchivePersonalDataController.java:92`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<?> putBookmark(@PathVariable String bookmarkId, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody(required = false) byte[] body, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `DELETE /archive/v1/me/bookmarks/{bookmarkId}`

- operationId：`ArchivePersonalDataController.deleteBookmark.DELETE`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchivePersonalDataController.java:107`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<?> deleteBookmark(@PathVariable String bookmarkId, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /archive/v1/me/notes`

- operationId：`ArchivePersonalDataController.notes.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchivePersonalDataController.java:125`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<?>> notes(@RequestParam String editionId, @RequestParam(required = false) String chapterId, @RequestParam(required = false) String cursor, @RequestParam(required = false) String limit, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `PUT /archive/v1/me/notes/{noteId}`

- operationId：`ArchivePersonalDataController.putNote.PUT`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchivePersonalDataController.java:146`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<?> putNote(@PathVariable String noteId, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody(required = false) byte[] body, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `DELETE /archive/v1/me/notes/{noteId}`

- operationId：`ArchivePersonalDataController.deleteNote.DELETE`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchivePersonalDataController.java:161`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<?> deleteNote(@PathVariable String noteId, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `PUT /archive/v1/me/questions/{questionId}`

- operationId：`ArchiveQuestionController.create.PUT`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchiveQuestionController.java:50`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<?> create(@PathVariable String questionId, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody(required = false) byte[] body, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /archive/v1/me/questions/{questionId}`

- operationId：`ArchiveQuestionController.get.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchiveQuestionController.java:64`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<?>> get(@PathVariable String questionId, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /archive/v1/me/questions/{questionId}/events`

- operationId：`ArchiveQuestionController.events.GET`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchiveQuestionController.java:78`
- 标记：STREAM, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<?> events(@PathVariable String questionId, @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId, Authentication authentication)
```

- 目标封装规则：显式字节/流契约；禁止JSON包装已开始传输的内容。错误、HEAD/Range/断流按专属契约定义。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /archive/v1/me/questions/{questionId}/retry`

- operationId：`ArchiveQuestionController.retry.POST`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchiveQuestionController.java:97`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<?> retry(@PathVariable String questionId, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody(required = false) byte[] body, HttpServletRequest request, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /chat/speech/synthesis`

- operationId：`SpeechSynthesisController.synthesize.POST`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/voice/api/SpeechSynthesisController.java:37`
- 标记：BYTE_RESPONSE_REQUIRES_MEDIA_CHECK
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<byte[]> synthesize( @RequestBody VoiceSynthesisRequest rawRequest, Authentication authentication)
```

- 目标封装规则：返回byte[]不等于二进制文件：先核实际Content-Type/序列化。若为自有application/json仍必须统一信封；真实文件才走字节例外。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /chat/speech/transcriptions`

- operationId：`SpeechTranscriptionController.transcribe.POST`
- 源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/voice/api/SpeechTranscriptionController.java:47`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<JsonResult<VoiceTranscriptionResponse>> transcribe( HttpServletRequest servletRequest, Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

