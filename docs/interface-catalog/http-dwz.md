# dwz：逐HTTP接口源码清单

基线 `7e2d07a05b9e9b18461e97abcff44642504320ff`；7 个method/path条目。源代码声明不是目标契约已完成。公共封装/权限/幂等见[目录说明](README.md)，人工复核关键链见[逐接口契约](critical-contracts.md)。

## `GET /dwz/view/{uri:.+}/**`

- operationId：`DwzController.view.GET`
- 源码：`dwz/jia-dwz-service/src/main/java/cn/jia/dwz/api/DwzController.java:35`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public String view(@PathVariable String uri, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /dwz/get`

- operationId：`DwzController.findById.GET`
- 源码：`dwz/jia-dwz-service/src/main/java/cn/jia/dwz/api/DwzController.java:64`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object findById(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /dwz/gen`

- operationId：`DwzController.gen.POST`
- 源码：`dwz/jia-dwz-service/src/main/java/cn/jia/dwz/api/DwzController.java:77`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object gen(@RequestBody DwzRecordEntity record)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /dwz/restore`

- operationId：`DwzController.restore.GET`
- 源码：`dwz/jia-dwz-service/src/main/java/cn/jia/dwz/api/DwzController.java:90`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object restore(@RequestParam(name = "uri") String uri)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /dwz/update`

- operationId：`DwzController.update.POST`
- 源码：`dwz/jia-dwz-service/src/main/java/cn/jia/dwz/api/DwzController.java:103`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object update(@RequestBody DwzRecordEntity record)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `DELETE /dwz/delete`

- operationId：`DwzController.delete.DELETE`
- 源码：`dwz/jia-dwz-service/src/main/java/cn/jia/dwz/api/DwzController.java:116`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object delete(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /dwz/list`

- operationId：`DwzController.list.POST`
- 源码：`dwz/jia-dwz-service/src/main/java/cn/jia/dwz/api/DwzController.java:129`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object list(@RequestBody JsonRequestPage<DwzRecordEntity> page)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

