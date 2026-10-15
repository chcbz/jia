# kefu：逐HTTP接口源码清单

基线 `7e2d07a05b9e9b18461e97abcff44642504320ff`；11 个method/path条目。源代码声明不是目标契约已完成。公共封装/权限/幂等见[目录说明](README.md)，人工复核关键链见[逐接口契约](critical-contracts.md)。

## `POST /kefu/faq/list`

- operationId：`KefuController.listFaq.POST`
- 源码：`kefu/jia-kefu-service/src/main/java/cn/jia/kefu/api/KefuController.java:55`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object listFaq(@RequestBody JsonRequestPage<KefuFaqEntity> page)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /kefu/faq/get`

- operationId：`KefuController.findFaqById.GET`
- 源码：`kefu/jia-kefu-service/src/main/java/cn/jia/kefu/api/KefuController.java:76`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object findFaqById(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /kefu/faq/create`

- operationId：`KefuController.createFaq.POST`
- 源码：`kefu/jia-kefu-service/src/main/java/cn/jia/kefu/api/KefuController.java:92`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object createFaq(@RequestBody KefuFaqEntity record)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /kefu/faq/update`

- operationId：`KefuController.updateFaq.POST`
- 源码：`kefu/jia-kefu-service/src/main/java/cn/jia/kefu/api/KefuController.java:106`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object updateFaq(@RequestBody KefuFaqEntity record)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /kefu/faq/delete`

- operationId：`KefuController.deleteFaq.GET`
- 源码：`kefu/jia-kefu-service/src/main/java/cn/jia/kefu/api/KefuController.java:119`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object deleteFaq(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /kefu/message/list`

- operationId：`KefuController.listMessage.POST`
- 源码：`kefu/jia-kefu-service/src/main/java/cn/jia/kefu/api/KefuController.java:136`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object listMessage(@RequestBody JsonRequestPage<KefuMessageEntity> page)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /kefu/message/get`

- operationId：`KefuController.findMessageById.GET`
- 源码：`kefu/jia-kefu-service/src/main/java/cn/jia/kefu/api/KefuController.java:158`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object findMessageById(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /kefu/message/create`

- operationId：`KefuController.createMessage.POST`
- 源码：`kefu/jia-kefu-service/src/main/java/cn/jia/kefu/api/KefuController.java:173`
- 标记：MULTIPART, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object createMessage(@RequestPart(required = false, value = "attach") MultipartFile file, KefuMessageEntity record)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /kefu/message/update`

- operationId：`KefuController.updateMessage.POST`
- 源码：`kefu/jia-kefu-service/src/main/java/cn/jia/kefu/api/KefuController.java:195`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object updateMessage(@RequestBody KefuMessageEntity record)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /kefu/message/delete`

- operationId：`KefuController.deleteMessage.GET`
- 源码：`kefu/jia-kefu-service/src/main/java/cn/jia/kefu/api/KefuController.java:208`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object deleteMessage(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /kefu/image/upload`

- operationId：`KefuController.updateLogo.POST`
- 源码：`kefu/jia-kefu-service/src/main/java/cn/jia/kefu/api/KefuController.java:226`
- 标记：MULTIPART, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object updateLogo(@RequestPart MultipartFile file)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

