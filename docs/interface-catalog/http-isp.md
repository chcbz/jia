# isp：逐HTTP接口源码清单

基线 `7e2d07a05b9e9b18461e97abcff44642504320ff`；61 个method/path条目。源代码声明不是目标契约已完成。公共封装/权限/幂等见[目录说明](README.md)，人工复核关键链见[逐接口契约](critical-contracts.md)。

## `POST /car/list/brand`

- operationId：`CarController.listBrand.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:40`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object listBrand(@RequestBody JsonRequestPage<CarBrandEntity> page, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /car/brand/get`

- operationId：`CarController.findBrandById.GET`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:57`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object findBrandById(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /car/brand/create`

- operationId：`CarController.createBrand.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:71`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object createBrand(@RequestBody CarBrandEntity record)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /car/brand/update`

- operationId：`CarController.updateBrand.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:82`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object updateBrand(@RequestBody CarBrandEntity record)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /car/brand/delete`

- operationId：`CarController.deleteBrand.GET`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:93`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object deleteBrand(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /car/list/brandAudi`

- operationId：`CarController.listBrandAudi.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:109`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object listBrandAudi(@RequestBody JsonRequestPage<CarBrandAudiEntity> page, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /car/brandAudi/get`

- operationId：`CarController.findBrandAudiById.GET`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:125`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object findBrandAudiById(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /car/brandAudi/create`

- operationId：`CarController.createBrandAudi.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:139`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object createBrandAudi(@RequestBody CarBrandAudiEntity record)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /car/brandAudi/update`

- operationId：`CarController.updateBrandAudi.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:150`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object updateBrandAudi(@RequestBody CarBrandAudiEntity record)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /car/brandAudi/delete`

- operationId：`CarController.deleteBrandAudi.GET`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:161`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object deleteBrandAudi(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /car/list/brandVersion`

- operationId：`CarController.listBrandVersion.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:177`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object listBrandVersion(@RequestBody JsonRequestPage<CarBrandVersionEntity> page, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /car/brandVersion/get`

- operationId：`CarController.findBrandVersionById.GET`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:193`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object findBrandVersionById(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /car/brandVersion/create`

- operationId：`CarController.createBrandVersion.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:207`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object createBrandVersion(@RequestBody CarBrandVersionEntity record)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /car/brandVersion/update`

- operationId：`CarController.updateBrandVersion.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:218`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object updateBrandVersion(@RequestBody CarBrandVersionEntity record)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /car/brandVersion/delete`

- operationId：`CarController.deleteBrandVersion.GET`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:229`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object deleteBrandVersion(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /car/list/brandMf`

- operationId：`CarController.listBrandMf.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:245`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object listBrandMf(@RequestBody JsonRequestPage<CarBrandMfEntity> page, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /car/brandMf/get`

- operationId：`CarController.findBrandMfById.GET`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:261`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object findBrandMfById(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /car/brandMf/create`

- operationId：`CarController.createBrandMf.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:275`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object createBrandMf(@RequestBody CarBrandMfEntity record)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /car/brandMf/update`

- operationId：`CarController.updateBrandMf.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:286`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object updateBrandMf(@RequestBody CarBrandMfEntity record)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /car/brandMf/delete`

- operationId：`CarController.deleteBrandMf.GET`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:297`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object deleteBrandMf(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /cms/table/list`

- operationId：`CmsController.listTable.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:48`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object listTable(@RequestBody JsonRequestPage<CmsTableEntity> page, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /cms/table/get`

- operationId：`CmsController.findTableById.GET`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:66`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object findTableById(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /cms/table/create`

- operationId：`CmsController.createTable.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:81`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object createTable(@RequestBody CmsTableDTO record)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /cms/table/update`

- operationId：`CmsController.updateTable.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:93`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object updateTable(@RequestBody CmsTableEntity record)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /cms/table/delete`

- operationId：`CmsController.deleteTable.GET`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:105`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object deleteTable(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /cms/column/list`

- operationId：`CmsController.listColumn.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:118`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object listColumn(@RequestBody JsonRequestPage<CmsColumnEntity> page, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /cms/column/get`

- operationId：`CmsController.findColumnById.GET`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:135`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object findColumnById(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /cms/column/create`

- operationId：`CmsController.createColumn.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:150`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object createColumn(@RequestBody CmsColumnEntity record)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /cms/column/update`

- operationId：`CmsController.updateColumn.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:162`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object updateColumn(@RequestBody CmsColumnEntity record)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /cms/column/delete`

- operationId：`CmsController.deleteColumn.GET`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:174`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object deleteColumn(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /cms/config/get`

- operationId：`CmsController.findConfig.GET`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:185`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object findConfig()
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /cms/config/update`

- operationId：`CmsController.updateConfig.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:197`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object updateConfig(@RequestBody CmsConfigEntity config)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /cms/row/list`

- operationId：`CmsController.listRow.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:227`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object listRow(@RequestBody JsonRequestPage<CmsRowExample> page, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /cms/row/get`

- operationId：`CmsController.findRowById.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:247`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object findRowById(@RequestBody CmsRowDTO cmsTable, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /cms/row/create`

- operationId：`CmsController.createRow.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:266`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object createRow(@RequestBody CmsRowDTO record, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /cms/row/update`

- operationId：`CmsController.updateRow.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:317`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object updateRow(@RequestBody CmsRowDTO record, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /cms/row/delete`

- operationId：`CmsController.deleteRow.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:405`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object deleteRow(@RequestBody CmsRowDTO record, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /file/res/{uri:.+}/**`

- operationId：`FileController.findByURI.GET`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/FileController.java:41`
- 标记：BYTE_RESPONSE_REQUIRES_MEDIA_CHECK
- 当前声明（含入参位置、Java类型及默认值）：

```java
public byte[] findByURI(@PathVariable String uri, HttpServletRequest request, HttpServletResponse response)
```

- 目标封装规则：返回byte[]不等于二进制文件：先核实际Content-Type/序列化。若为自有application/json仍必须统一信封；真实文件才走字节例外。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /isp/server/list`

- operationId：`IspController.listServer.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/IspController.java:47`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object listServer(@RequestBody JsonRequestPage<IspServerEntity> page, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /isp/server/get`

- operationId：`IspController.findServerById.GET`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/IspController.java:65`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object findServerById(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /isp/server/create`

- operationId：`IspController.createServer.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/IspController.java:80`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object createServer(@RequestBody IspServerEntity record)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /isp/server/update`

- operationId：`IspController.updateServer.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/IspController.java:93`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object updateServer(@RequestBody IspServerEntity record)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /isp/server/delete`

- operationId：`IspController.deleteServer.GET`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/IspController.java:105`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object deleteServer(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /isp/server/ssl/refresh`

- operationId：`IspController.refreshSSL.GET.336d2a9d3bb2`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/IspController.java:118`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object refreshSSL(@RequestParam Long id, @RequestParam String domain)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /isp/domain/record/delete`

- operationId：`IspController.refreshSSL.GET.725889b1d8f0`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/IspController.java:124`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object refreshSSL(@RequestParam String name, @RequestParam String domain)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /isp/dns/hosts`

- operationId：`IspController.hosts.GET`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/IspController.java:136`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object hosts(DnsRecordDTO dnsRecordDTO)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `ANY /isp/dns/update_record`

- operationId：`IspController.updateDnsRecord.ANY`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/IspController.java:163`
- 标记：METHOD_UNRESTRICTED, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object updateDnsRecord(@RequestParam String name, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /ldap/user/get`

- operationId：`LdapController.userFind.GET`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:59`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object userFind(@RequestParam(name = "type", defaultValue = "uid") String type, @RequestParam(name = "key") String key)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /ldap/user/search`

- operationId：`LdapController.userSearch.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:86`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object userSearch(@RequestBody LdapUser user)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /ldap/user/create`

- operationId：`LdapController.userCreate.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:104`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object userCreate(@RequestBody LdapUser user)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /ldap/user/update`

- operationId：`LdapController.userUpdate.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:130`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object userUpdate(@RequestBody LdapUser user)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /ldap/user/delete`

- operationId：`LdapController.userDelete.GET`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:144`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object userDelete(@RequestParam(name = "uid") String uid)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /ldap/user/findAll`

- operationId：`LdapController.userFindAll.GET`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:157`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object userFindAll()
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /ldap/usergroup/findByCn`

- operationId：`LdapController.userGroupFindByCn.GET`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:174`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object userGroupFindByCn(@RequestParam(name = "cn") String cn)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /ldap/usergroup/findAll`

- operationId：`LdapController.userGroupFindAll.GET`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:185`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object userGroupFindAll()
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /ldap/usergroup/create`

- operationId：`LdapController.userGroupCreate.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:199`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object userGroupCreate(@RequestBody LdapUserGroupDTO userGroup)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /ldap/usergroup/update`

- operationId：`LdapController.userGroupUpdate.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:227`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object userGroupUpdate(@RequestBody LdapUserGroup userGroup)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /ldap/usergroup/member/add`

- operationId：`LdapController.userGroupMemberAdd.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:242`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object userGroupMemberAdd(@RequestBody LdapUserGroupDTO userGroup)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /ldap/usergroup/member/delete`

- operationId：`LdapController.userGroupMemberDelete.POST`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:267`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object userGroupMemberDelete(@RequestBody LdapUserGroupDTO userGroup)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /ldap/usergroup/delete`

- operationId：`LdapController.userGroupDelete.GET`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:290`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object userGroupDelete(@RequestParam(name = "cn") String cn)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /ldap/account/findByUid`

- operationId：`LdapController.accountFindByUid.GET`
- 源码：`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:304`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object accountFindByUid(@RequestParam(name = "uid") String uid)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

