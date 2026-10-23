# base：逐HTTP接口源码清单

基线 `7e2d07a05b9e9b18461e97abcff44642504320ff`；7 个method/path条目。源代码声明不是目标契约已完成。公共封装/权限/幂等见[目录说明](README.md)，人工复核关键链见[逐接口契约](critical-contracts.md)。

## `GET /dict/get`

- operationId：`DictController.find.GET`
- 源码：`base/jia-base-service/src/main/java/cn/jia/base/api/DictController.java:32`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object find(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /dict/create`

- operationId：`DictController.create.POST`
- 源码：`base/jia-base-service/src/main/java/cn/jia/base/api/DictController.java:45`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object create(@RequestBody DictEntity dict)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /dict/update`

- operationId：`DictController.update.POST`
- 源码：`base/jia-base-service/src/main/java/cn/jia/base/api/DictController.java:58`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object update(@RequestBody DictEntity dict)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /dict/list`

- operationId：`DictController.list.POST`
- 源码：`base/jia-base-service/src/main/java/cn/jia/base/api/DictController.java:71`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object list(@RequestBody JsonRequestPage<DictEntity> page)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `DELETE /dict/delete`

- operationId：`DictController.delete.DELETE`
- 源码：`base/jia-base-service/src/main/java/cn/jia/base/api/DictController.java:87`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object delete(@RequestParam("id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /dict/locale`

- operationId：`DictController.locale.GET`
- 源码：`base/jia-base-service/src/main/java/cn/jia/base/api/DictController.java:99`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object locale()
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /dict/cleanCache`

- operationId：`DictController.cleanCache.GET`
- 源码：`base/jia-base-service/src/main/java/cn/jia/base/api/DictController.java:109`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object cleanCache()
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

