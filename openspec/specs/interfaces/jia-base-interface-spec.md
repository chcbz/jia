# Base 基础模块接口文档

来源模块：`base`

本文面向前端对接，每个接口包含请求方式、地址、参数位置、必填性、请求体字段和响应结构。

## 通用响应结构

- 单对象或操作结果：`JsonResult<T>`，字段：`code`、`msg`、`status`、`location`、`data`。
- 分页列表：`JsonResultPage<T>`，字段：`code`、`msg`、`status`、`pageNum`、`total`、`data`（数组）。
- 成功码默认为 `E0`，HTTP 状态通常为 `200`。

## 接口汇总

| 请求方式 | 地址 | Controller | 响应 |
| --- | --- | --- | --- |
| GET | `/dict/get` | `DictController.find` | JsonResult<[DictEntity](#model-dictentity)> |
| POST | `/dict/create` | `DictController.create` | JsonResult<Void> |
| POST | `/dict/update` | `DictController.update` | JsonResult<Void> |
| POST | `/dict/list` | `DictController.list` | JsonResultPage<[DictEntity](#model-dictentity)> |
| DELETE | `/dict/delete` | `DictController.delete` | JsonResult<Void> |
| GET | `/dict/locale` | `DictController.locale` | JsonResult<dictService.selectAll(LocaleContextHolder.getLocal> |
| GET | `/dict/cleanCache` | `DictController.cleanCache` | JsonResult<Void> |

## 接口明细

### DictController `/dict`

源码：`base/jia-base-service/src/main/java/cn/jia/base/api/DictController.java`

#### GET `/dict/get`

- 处理方法：`DictController.find`
- 响应：[JsonResult](#model-jsonresult)<[DictEntity](#model-dictentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<DictEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/dict/create`

- 处理方法：`DictController.create`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `dict` | body | yes | [DictEntity](#model-dictentity) | 请求体 |

请求体字段：

[DictEntity](#model-dictentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `DictEntity` |
| `updateTime` | `Long` | 源自 `DictEntity` |
| `tenantId` | `String` | 源自 `DictEntity` |
| `clientId` | `String` | 源自 `DictEntity` |
| `id` | `Long` | 源自 `DictEntity` |
| `type` | `String` | 源自 `DictEntity` |
| `language` | `String` | 源自 `DictEntity` |
| `name` | `String` | 源自 `DictEntity` |
| `value` | `String` | 源自 `DictEntity` |
| `url` | `String` | 源自 `DictEntity` |
| `parentId` | `String` | 源自 `DictEntity` |
| `dictOrder` | `Integer` | 源自 `DictEntity` |
| `description` | `String` | 源自 `DictEntity` |
| `status` | `Integer` | 源自 `DictEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/dict/update`

- 处理方法：`DictController.update`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `dict` | body | yes | [DictEntity](#model-dictentity) | 请求体 |

请求体字段：

[DictEntity](#model-dictentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `DictEntity` |
| `updateTime` | `Long` | 源自 `DictEntity` |
| `tenantId` | `String` | 源自 `DictEntity` |
| `clientId` | `String` | 源自 `DictEntity` |
| `id` | `Long` | 源自 `DictEntity` |
| `type` | `String` | 源自 `DictEntity` |
| `language` | `String` | 源自 `DictEntity` |
| `name` | `String` | 源自 `DictEntity` |
| `value` | `String` | 源自 `DictEntity` |
| `url` | `String` | 源自 `DictEntity` |
| `parentId` | `String` | 源自 `DictEntity` |
| `dictOrder` | `Integer` | 源自 `DictEntity` |
| `description` | `String` | 源自 `DictEntity` |
| `status` | `Integer` | 源自 `DictEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/dict/list`

- 处理方法：`DictController.list`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[DictEntity](#model-dictentity)> | 请求体 |

请求体字段：

[DictEntity](#model-dictentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `DictEntity` |
| `updateTime` | `Long` | 源自 `DictEntity` |
| `tenantId` | `String` | 源自 `DictEntity` |
| `clientId` | `String` | 源自 `DictEntity` |
| `id` | `Long` | 源自 `DictEntity` |
| `type` | `String` | 源自 `DictEntity` |
| `language` | `String` | 源自 `DictEntity` |
| `name` | `String` | 源自 `DictEntity` |
| `value` | `String` | 源自 `DictEntity` |
| `url` | `String` | 源自 `DictEntity` |
| `parentId` | `String` | 源自 `DictEntity` |
| `dictOrder` | `Integer` | 源自 `DictEntity` |
| `description` | `String` | 源自 `DictEntity` |
| `status` | `Integer` | 源自 `DictEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### DELETE `/dict/delete`

- 处理方法：`DictController.delete`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/dict/locale`

- 处理方法：`DictController.locale`
- 响应：[JsonResult](#model-jsonresult)<expression: dictService.selectAll(LocaleContextHolder.getLocale().toStri>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| - | - | - | - | 无入参 |

响应 data 说明：
- 按 `JsonResult<expression: dictService.selectAll(LocaleContextHolder.getLocale().toStri>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/dict/cleanCache`

- 处理方法：`DictController.cleanCache`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| - | - | - | - | 无入参 |

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

### Model DictEntity

源码：`base/jia-base-core/src/main/java/cn/jia/base/entity/DictEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `type` | `type` | `String` |
| `language` | `language` | `String` |
| `name` | `name` | `String` |
| `value` | `value` | `String` |
| `url` | `url` | `String` |
| `parentId` | `parentId` | `String` |
| `dictOrder` | `dictOrder` | `Integer` |
| `description` | `description` | `String` |
| `status` | `status` | `Integer` |

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
