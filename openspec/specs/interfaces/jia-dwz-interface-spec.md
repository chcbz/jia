# DWZ 短链接模块接口文档

来源模块：`dwz`

本文面向前端对接，每个接口包含请求方式、地址、参数位置、必填性、请求体字段和响应结构。

## 通用响应结构

- 单对象或操作结果：`JsonResult<T>`，字段：`code`、`msg`、`status`、`location`、`data`。
- 分页列表：`JsonResultPage<T>`，字段：`code`、`msg`、`status`、`pageNum`、`total`、`data`（数组）。
- 成功码默认为 `E0`，HTTP 状态通常为 `200`。

## 接口汇总

| 请求方式 | 地址 | Controller | 响应 |
| --- | --- | --- | --- |
| GET | `/dwz/view/{uri:.+}/**` | `DwzController.view` | String |
| GET | `/dwz/get` | `DwzController.findById` | JsonResult<[DwzRecordEntity](#model-dwzrecordentity)> |
| POST | `/dwz/gen` | `DwzController.gen` | JsonResult<String> |
| GET | `/dwz/restore` | `DwzController.restore` | JsonResult<record.getOrig()> |
| POST | `/dwz/update` | `DwzController.update` | JsonResult<record> |
| DELETE | `/dwz/delete` | `DwzController.delete` | JsonResult<Void> |
| POST | `/dwz/list` | `DwzController.list` | JsonResultPage<[DwzRecordEntity](#model-dwzrecordentity)> |

## 接口明细

### DwzController `/dwz`

源码：`dwz/jia-dwz-service/src/main/java/cn/jia/dwz/api/DwzController.java`

#### GET `/dwz/view/{uri:.+}/**`

- 处理方法：`DwzController.view`
- 响应：String

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `uri` | path | yes | String | 路径参数 |

响应 data 说明：
- 按 `String` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/dwz/get`

- 处理方法：`DwzController.findById`
- 响应：[JsonResult](#model-jsonresult)<[DwzRecordEntity](#model-dwzrecordentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<DwzRecordEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/dwz/gen`

- 处理方法：`DwzController.gen`
- 响应：[JsonResult](#model-jsonresult)<String>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `record` | body | yes | [DwzRecordEntity](#model-dwzrecordentity) | 请求体 |

请求体字段：

[DwzRecordEntity](#model-dwzrecordentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `DwzRecordEntity` |
| `updateTime` | `Long` | 源自 `DwzRecordEntity` |
| `tenantId` | `String` | 源自 `DwzRecordEntity` |
| `clientId` | `String` | 源自 `DwzRecordEntity` |
| `id` | `Long` | 源自 `DwzRecordEntity` |
| `jiacn` | `String` | 源自 `DwzRecordEntity` |
| `orig` | `String` | 源自 `DwzRecordEntity` |
| `uri` | `String` | 源自 `DwzRecordEntity` |
| `expireTime` | `Long` | 源自 `DwzRecordEntity` |
| `status` | `Integer` | 源自 `DwzRecordEntity` |
| `pv` | `Integer` | 源自 `DwzRecordEntity` |

响应 data 说明：
- 按 `JsonResult<String>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/dwz/restore`

- 处理方法：`DwzController.restore`
- 响应：[JsonResult](#model-jsonresult)<expression: record.getOrig()>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `uri` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<expression: record.getOrig()>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/dwz/update`

- 处理方法：`DwzController.update`
- 响应：[JsonResult](#model-jsonresult)<[DwzRecordEntity](#model-dwzrecordentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `record` | body | yes | [DwzRecordEntity](#model-dwzrecordentity) | 请求体 |

请求体字段：

[DwzRecordEntity](#model-dwzrecordentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `DwzRecordEntity` |
| `updateTime` | `Long` | 源自 `DwzRecordEntity` |
| `tenantId` | `String` | 源自 `DwzRecordEntity` |
| `clientId` | `String` | 源自 `DwzRecordEntity` |
| `id` | `Long` | 源自 `DwzRecordEntity` |
| `jiacn` | `String` | 源自 `DwzRecordEntity` |
| `orig` | `String` | 源自 `DwzRecordEntity` |
| `uri` | `String` | 源自 `DwzRecordEntity` |
| `expireTime` | `Long` | 源自 `DwzRecordEntity` |
| `status` | `Integer` | 源自 `DwzRecordEntity` |
| `pv` | `Integer` | 源自 `DwzRecordEntity` |

响应 data 说明：
- 按 `JsonResult<DwzRecordEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### DELETE `/dwz/delete`

- 处理方法：`DwzController.delete`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/dwz/list`

- 处理方法：`DwzController.list`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[DwzRecordEntity](#model-dwzrecordentity)> | 请求体 |

请求体字段：

[DwzRecordEntity](#model-dwzrecordentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `DwzRecordEntity` |
| `updateTime` | `Long` | 源自 `DwzRecordEntity` |
| `tenantId` | `String` | 源自 `DwzRecordEntity` |
| `clientId` | `String` | 源自 `DwzRecordEntity` |
| `id` | `Long` | 源自 `DwzRecordEntity` |
| `jiacn` | `String` | 源自 `DwzRecordEntity` |
| `orig` | `String` | 源自 `DwzRecordEntity` |
| `uri` | `String` | 源自 `DwzRecordEntity` |
| `expireTime` | `Long` | 源自 `DwzRecordEntity` |
| `status` | `Integer` | 源自 `DwzRecordEntity` |
| `pv` | `Integer` | 源自 `DwzRecordEntity` |

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

### Model DwzRecordEntity

源码：`dwz/jia-dwz-core/src/main/java/cn/jia/dwz/entity/DwzRecordEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `jiacn` | `jiacn` | `String` |
| `orig` | `orig` | `String` |
| `uri` | `uri` | `String` |
| `expireTime` | `expireTime` | `Long` |
| `status` | `status` | `Integer` |
| `pv` | `pv` | `Integer` |

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
