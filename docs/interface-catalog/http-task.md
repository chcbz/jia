# task：逐HTTP接口源码清单

基线 `7e2d07a05b9e9b18461e97abcff44642504320ff`；12 个method/path条目。源代码声明不是目标契约已完成。公共封装/权限/幂等见[目录说明](README.md)，人工复核关键链见[逐接口契约](critical-contracts.md)。

## `GET /job/list`

- operationId：`JobController.list.GET`
- 源码：`task/jia-task-service/src/main/java/cn/jia/task/api/JobController.java:37`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object list()
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /job/get`

- operationId：`JobController.get.GET`
- 源码：`task/jia-task-service/src/main/java/cn/jia/task/api/JobController.java:50`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object get(@RequestParam String name)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /job/execute`

- operationId：`JobController.execute.POST`
- 源码：`task/jia-task-service/src/main/java/cn/jia/task/api/JobController.java:72`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object execute(@RequestBody JobExecuteRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /job/execute/async`

- operationId：`JobController.executeAsync.POST`
- 源码：`task/jia-task-service/src/main/java/cn/jia/task/api/JobController.java:88`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object executeAsync(@RequestBody JobExecuteRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /job/status`

- operationId：`JobController.status.GET`
- 源码：`task/jia-task-service/src/main/java/cn/jia/task/api/JobController.java:104`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object status(@RequestParam String name)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /task/get`

- operationId：`TaskController.findById.GET`
- 源码：`task/jia-task-service/src/main/java/cn/jia/task/api/TaskController.java:36`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object findById(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /task/create`

- operationId：`TaskController.create.POST`
- 源码：`task/jia-task-service/src/main/java/cn/jia/task/api/TaskController.java:49`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object create(@RequestBody TaskPlanEntity task)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /task/update`

- operationId：`TaskController.update.POST`
- 源码：`task/jia-task-service/src/main/java/cn/jia/task/api/TaskController.java:63`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object update(@RequestBody TaskPlanEntity task)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `DELETE /task/delete`

- operationId：`TaskController.delete.DELETE`
- 源码：`task/jia-task-service/src/main/java/cn/jia/task/api/TaskController.java:76`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object delete(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `DELETE /task/cancel`

- operationId：`TaskController.cancel.DELETE`
- 源码：`task/jia-task-service/src/main/java/cn/jia/task/api/TaskController.java:89`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object cancel(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /task/search`

- operationId：`TaskController.search.POST`
- 源码：`task/jia-task-service/src/main/java/cn/jia/task/api/TaskController.java:101`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object search(@RequestBody JsonRequestPage<TaskPlanVO> page)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /task/item/search`

- operationId：`TaskController.searchItem.POST`
- 源码：`task/jia-task-service/src/main/java/cn/jia/task/api/TaskController.java:117`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object searchItem(@RequestBody JsonRequestPage<TaskDetailVO> page)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

