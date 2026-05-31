# Point 积分模块接口文档

来源模块：`point`

本文面向前端对接，每个接口包含请求方式、地址、参数位置、必填性、请求体字段和响应结构。

## 通用响应结构

- 单对象或操作结果：`JsonResult<T>`，字段：`code`、`msg`、`status`、`location`、`data`。
- 分页列表：`JsonResultPage<T>`，字段：`code`、`msg`、`status`、`pageNum`、`total`、`data`（数组）。
- 成功码默认为 `E0`，HTTP 状态通常为 `200`。

## 接口汇总

| 请求方式 | 地址 | Controller | 响应 |
| --- | --- | --- | --- |
| GET | `/gift/get` | `GiftController.findById` | JsonResult<[PointGiftEntity](#model-pointgiftentity)> |
| POST | `/gift/create` | `GiftController.create` | JsonResult<Void> |
| POST | `/gift/update` | `GiftController.update` | JsonResult<Void> |
| DELETE | `/gift/delete` | `GiftController.delete` | JsonResult<Void> |
| POST | `/gift/list` | `GiftController.list` | JsonResultPage<[PointGiftEntity](#model-pointgiftentity)> |
| POST | `/gift/usage/add` | `GiftController.usageAdd` | JsonResult<giftUsage> |
| POST | `/gift/usage/cancel/{giftUsageId}` | `GiftController.usageCancel` | JsonResult<giftUsageId> |
| POST | `/gift/usage/delete/{giftUsageId}` | `GiftController.usageDelete` | JsonResult<giftUsageId> |
| POST | `/gift/usage/list/gift/{giftId}` | `GiftController.usageListByGift` | JsonResultPage<[PointGiftUsageEntity](#model-pointgiftusageentity)> |
| POST | `/gift/usage/list/user/{user}` | `GiftController.usageListByUser` | JsonResultPage<[PointGiftUsageEntity](#model-pointgiftusageentity)> |
| POST | `/point/init` | `PointController.init` | JsonResult<record> |
| POST | `/point/sign` | `PointController.sign` | JsonResult<[PointRecordEntity](#model-pointrecordentity)> |
| POST | `/point/referral` | `PointController.referral` | JsonResult<[PointRecordEntity](#model-pointrecordentity)> |
| POST | `/point/luck` | `PointController.luck` | JsonResult<record> |

## 接口明细

### GiftController `/gift`

源码：`point/jia-point-service/src/main/java/cn/jia/point/api/GiftController.java`

#### GET `/gift/get`

- 处理方法：`GiftController.findById`
- 响应：[JsonResult](#model-jsonresult)<[PointGiftEntity](#model-pointgiftentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<PointGiftEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/gift/create`

- 处理方法：`GiftController.create`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `gift` | body | yes | [PointGiftEntity](#model-pointgiftentity) | 请求体 |

请求体字段：

[PointGiftEntity](#model-pointgiftentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `PointGiftEntity` |
| `updateTime` | `Long` | 源自 `PointGiftEntity` |
| `tenantId` | `String` | 源自 `PointGiftEntity` |
| `clientId` | `String` | 源自 `PointGiftEntity` |
| `id` | `Long` | 源自 `PointGiftEntity` |
| `name` | `String` | 源自 `PointGiftEntity` |
| `description` | `String` | 源自 `PointGiftEntity` |
| `picUrl` | `String` | 源自 `PointGiftEntity` |
| `point` | `Integer` | 源自 `PointGiftEntity` |
| `price` | `Integer` | 源自 `PointGiftEntity` |
| `quantity` | `Integer` | 源自 `PointGiftEntity` |
| `virtualFlag` | `Integer` | 源自 `PointGiftEntity` |
| `status` | `Integer` | 源自 `PointGiftEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/gift/update`

- 处理方法：`GiftController.update`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `gift` | body | yes | [PointGiftEntity](#model-pointgiftentity) | 请求体 |

请求体字段：

[PointGiftEntity](#model-pointgiftentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `PointGiftEntity` |
| `updateTime` | `Long` | 源自 `PointGiftEntity` |
| `tenantId` | `String` | 源自 `PointGiftEntity` |
| `clientId` | `String` | 源自 `PointGiftEntity` |
| `id` | `Long` | 源自 `PointGiftEntity` |
| `name` | `String` | 源自 `PointGiftEntity` |
| `description` | `String` | 源自 `PointGiftEntity` |
| `picUrl` | `String` | 源自 `PointGiftEntity` |
| `point` | `Integer` | 源自 `PointGiftEntity` |
| `price` | `Integer` | 源自 `PointGiftEntity` |
| `quantity` | `Integer` | 源自 `PointGiftEntity` |
| `virtualFlag` | `Integer` | 源自 `PointGiftEntity` |
| `status` | `Integer` | 源自 `PointGiftEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### DELETE `/gift/delete`

- 处理方法：`GiftController.delete`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/gift/list`

- 处理方法：`GiftController.list`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[PointGiftVO](#model-pointgiftvo)> | 请求体 |

请求体字段：

[PointGiftVO](#model-pointgiftvo)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `PointGiftVO` |
| `updateTime` | `Long` | 源自 `PointGiftVO` |
| `tenantId` | `String` | 源自 `PointGiftVO` |
| `clientId` | `String` | 源自 `PointGiftVO` |
| `id` | `Long` | 源自 `PointGiftVO` |
| `name` | `String` | 源自 `PointGiftVO` |
| `description` | `String` | 源自 `PointGiftVO` |
| `picUrl` | `String` | 源自 `PointGiftVO` |
| `point` | `Integer` | 源自 `PointGiftVO` |
| `price` | `Integer` | 源自 `PointGiftVO` |
| `quantity` | `Integer` | 源自 `PointGiftVO` |
| `virtualFlag` | `Integer` | 源自 `PointGiftVO` |
| `status` | `Integer` | 源自 `PointGiftVO` |
| `clientStrictFlag` | `Integer` | 源自 `PointGiftVO` |
| `createTimeStart` | `Long` | 源自 `PointGiftVO` |
| `createTimeEnd` | `Long` | 源自 `PointGiftVO` |
| `updateTimeStart` | `Long` | 源自 `PointGiftVO` |
| `updateTimeEnd` | `Long` | 源自 `PointGiftVO` |
| `nameLike` | `String` | 源自 `PointGiftVO` |
| `descriptionLike` | `String` | 源自 `PointGiftVO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/gift/usage/add`

- 处理方法：`GiftController.usageAdd`
- 响应：[JsonResult](#model-jsonresult)<[PointGiftUsageEntity](#model-pointgiftusageentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `giftUsage` | body | yes | [PointGiftUsageEntity](#model-pointgiftusageentity) | 请求体 |

请求体字段：

[PointGiftUsageEntity](#model-pointgiftusageentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `PointGiftUsageEntity` |
| `updateTime` | `Long` | 源自 `PointGiftUsageEntity` |
| `tenantId` | `String` | 源自 `PointGiftUsageEntity` |
| `clientId` | `String` | 源自 `PointGiftUsageEntity` |
| `id` | `Long` | 源自 `PointGiftUsageEntity` |
| `giftId` | `Long` | 源自 `PointGiftUsageEntity` |
| `name` | `String` | 源自 `PointGiftUsageEntity` |
| `description` | `String` | 源自 `PointGiftUsageEntity` |
| `picUrl` | `String` | 源自 `PointGiftUsageEntity` |
| `jiacn` | `String` | 源自 `PointGiftUsageEntity` |
| `quantity` | `Integer` | 源自 `PointGiftUsageEntity` |
| `point` | `Integer` | 源自 `PointGiftUsageEntity` |
| `price` | `Integer` | 源自 `PointGiftUsageEntity` |
| `consignee` | `String` | 源自 `PointGiftUsageEntity` |
| `phone` | `String` | 源自 `PointGiftUsageEntity` |
| `address` | `String` | 源自 `PointGiftUsageEntity` |
| `cardNo` | `String` | 源自 `PointGiftUsageEntity` |
| `status` | `Integer` | 源自 `PointGiftUsageEntity` |

响应 data 说明：
- 按 `JsonResult<PointGiftUsageEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/gift/usage/cancel/{giftUsageId}`

- 处理方法：`GiftController.usageCancel`
- 响应：[JsonResult](#model-jsonresult)<Long>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `giftUsageId` | path | yes | Long | 路径参数 |

响应 data 说明：
- 按 `JsonResult<Long>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/gift/usage/delete/{giftUsageId}`

- 处理方法：`GiftController.usageDelete`
- 响应：[JsonResult](#model-jsonresult)<Long>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `giftUsageId` | path | yes | Long | 路径参数 |

响应 data 说明：
- 按 `JsonResult<Long>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/gift/usage/list/gift/{giftId}`

- 处理方法：`GiftController.usageListByGift`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[PointGiftUsageEntity](#model-pointgiftusageentity)> | 请求体 |
| `giftId` | path | yes | Long | 路径参数 |

请求体字段：

[PointGiftUsageEntity](#model-pointgiftusageentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `PointGiftUsageEntity` |
| `updateTime` | `Long` | 源自 `PointGiftUsageEntity` |
| `tenantId` | `String` | 源自 `PointGiftUsageEntity` |
| `clientId` | `String` | 源自 `PointGiftUsageEntity` |
| `id` | `Long` | 源自 `PointGiftUsageEntity` |
| `giftId` | `Long` | 源自 `PointGiftUsageEntity` |
| `name` | `String` | 源自 `PointGiftUsageEntity` |
| `description` | `String` | 源自 `PointGiftUsageEntity` |
| `picUrl` | `String` | 源自 `PointGiftUsageEntity` |
| `jiacn` | `String` | 源自 `PointGiftUsageEntity` |
| `quantity` | `Integer` | 源自 `PointGiftUsageEntity` |
| `point` | `Integer` | 源自 `PointGiftUsageEntity` |
| `price` | `Integer` | 源自 `PointGiftUsageEntity` |
| `consignee` | `String` | 源自 `PointGiftUsageEntity` |
| `phone` | `String` | 源自 `PointGiftUsageEntity` |
| `address` | `String` | 源自 `PointGiftUsageEntity` |
| `cardNo` | `String` | 源自 `PointGiftUsageEntity` |
| `status` | `Integer` | 源自 `PointGiftUsageEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/gift/usage/list/user/{user}`

- 处理方法：`GiftController.usageListByUser`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[PointGiftUsageEntity](#model-pointgiftusageentity)> | 请求体 |
| `user` | path | yes | String | 路径参数 |

请求体字段：

[PointGiftUsageEntity](#model-pointgiftusageentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `PointGiftUsageEntity` |
| `updateTime` | `Long` | 源自 `PointGiftUsageEntity` |
| `tenantId` | `String` | 源自 `PointGiftUsageEntity` |
| `clientId` | `String` | 源自 `PointGiftUsageEntity` |
| `id` | `Long` | 源自 `PointGiftUsageEntity` |
| `giftId` | `Long` | 源自 `PointGiftUsageEntity` |
| `name` | `String` | 源自 `PointGiftUsageEntity` |
| `description` | `String` | 源自 `PointGiftUsageEntity` |
| `picUrl` | `String` | 源自 `PointGiftUsageEntity` |
| `jiacn` | `String` | 源自 `PointGiftUsageEntity` |
| `quantity` | `Integer` | 源自 `PointGiftUsageEntity` |
| `point` | `Integer` | 源自 `PointGiftUsageEntity` |
| `price` | `Integer` | 源自 `PointGiftUsageEntity` |
| `consignee` | `String` | 源自 `PointGiftUsageEntity` |
| `phone` | `String` | 源自 `PointGiftUsageEntity` |
| `address` | `String` | 源自 `PointGiftUsageEntity` |
| `cardNo` | `String` | 源自 `PointGiftUsageEntity` |
| `status` | `Integer` | 源自 `PointGiftUsageEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

### PointController `/point`

源码：`point/jia-point-service/src/main/java/cn/jia/point/api/PointController.java`

#### POST `/point/init`

- 处理方法：`PointController.init`
- 响应：[JsonResult](#model-jsonresult)<[PointRecordEntity](#model-pointrecordentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `record` | body | yes | [PointRecordEntity](#model-pointrecordentity) | 请求体 |

请求体字段：

[PointRecordEntity](#model-pointrecordentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `PointRecordEntity` |
| `updateTime` | `Long` | 源自 `PointRecordEntity` |
| `tenantId` | `String` | 源自 `PointRecordEntity` |
| `clientId` | `String` | 源自 `PointRecordEntity` |
| `id` | `Long` | 源自 `PointRecordEntity` |
| `jiacn` | `String` | 源自 `PointRecordEntity` |
| `type` | `Integer` | 源自 `PointRecordEntity` |
| `chg` | `Integer` | 源自 `PointRecordEntity` |
| `remain` | `Integer` | 源自 `PointRecordEntity` |

响应 data 说明：
- 按 `JsonResult<PointRecordEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/point/sign`

- 处理方法：`PointController.sign`
- 响应：[JsonResult](#model-jsonresult)<[PointRecordEntity](#model-pointrecordentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `sign` | body | yes | [PointSignEntity](#model-pointsignentity) | 请求体 |

请求体字段：

[PointSignEntity](#model-pointsignentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `PointSignEntity` |
| `updateTime` | `Long` | 源自 `PointSignEntity` |
| `tenantId` | `String` | 源自 `PointSignEntity` |
| `clientId` | `String` | 源自 `PointSignEntity` |
| `id` | `Long` | 源自 `PointSignEntity` |
| `jiacn` | `String` | 源自 `PointSignEntity` |
| `address` | `String` | 源自 `PointSignEntity` |
| `latitude` | `String` | 源自 `PointSignEntity` |
| `longitude` | `String` | 源自 `PointSignEntity` |
| `point` | `Integer` | 源自 `PointSignEntity` |

响应 data 说明：
- 按 `JsonResult<PointRecordEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/point/referral`

- 处理方法：`PointController.referral`
- 响应：[JsonResult](#model-jsonresult)<[PointRecordEntity](#model-pointrecordentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `referral` | body | yes | [PointReferralEntity](#model-pointreferralentity) | 请求体 |

请求体字段：

[PointReferralEntity](#model-pointreferralentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `PointReferralEntity` |
| `updateTime` | `Long` | 源自 `PointReferralEntity` |
| `tenantId` | `String` | 源自 `PointReferralEntity` |
| `clientId` | `String` | 源自 `PointReferralEntity` |
| `id` | `Long` | 源自 `PointReferralEntity` |
| `referrer` | `String` | 源自 `PointReferralEntity` |
| `referral` | `String` | 源自 `PointReferralEntity` |

响应 data 说明：
- 按 `JsonResult<PointRecordEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/point/luck`

- 处理方法：`PointController.luck`
- 响应：[JsonResult](#model-jsonresult)<[PointRecordEntity](#model-pointrecordentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `record` | body | yes | [PointRecordEntity](#model-pointrecordentity) | 请求体 |

请求体字段：

[PointRecordEntity](#model-pointrecordentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `PointRecordEntity` |
| `updateTime` | `Long` | 源自 `PointRecordEntity` |
| `tenantId` | `String` | 源自 `PointRecordEntity` |
| `clientId` | `String` | 源自 `PointRecordEntity` |
| `id` | `Long` | 源自 `PointRecordEntity` |
| `jiacn` | `String` | 源自 `PointRecordEntity` |
| `type` | `Integer` | 源自 `PointRecordEntity` |
| `chg` | `Integer` | 源自 `PointRecordEntity` |
| `remain` | `Integer` | 源自 `PointRecordEntity` |

响应 data 说明：
- 按 `JsonResult<PointRecordEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

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

### Model PointGiftEntity

源码：`point/jia-point-core/src/main/java/cn/jia/point/entity/PointGiftEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `name` | `name` | `String` |
| `description` | `description` | `String` |
| `picUrl` | `picUrl` | `String` |
| `point` | `point` | `Integer` |
| `price` | `price` | `Integer` |
| `quantity` | `quantity` | `Integer` |
| `virtualFlag` | `virtualFlag` | `Integer` |
| `status` | `status` | `Integer` |

### Model PointGiftUsageEntity

源码：`point/jia-point-core/src/main/java/cn/jia/point/entity/PointGiftUsageEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `giftId` | `giftId` | `Long` |
| `name` | `name` | `String` |
| `description` | `description` | `String` |
| `picUrl` | `picUrl` | `String` |
| `jiacn` | `jiacn` | `String` |
| `quantity` | `quantity` | `Integer` |
| `point` | `point` | `Integer` |
| `price` | `price` | `Integer` |
| `consignee` | `consignee` | `String` |
| `phone` | `phone` | `String` |
| `address` | `address` | `String` |
| `cardNo` | `cardNo` | `String` |
| `status` | `status` | `Integer` |

### Model PointGiftVO

源码：`point/jia-point-api/src/main/java/cn/jia/point/entity/PointGiftVO.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `name` | `name` | `String` |
| `description` | `description` | `String` |
| `picUrl` | `picUrl` | `String` |
| `point` | `point` | `Integer` |
| `price` | `price` | `Integer` |
| `quantity` | `quantity` | `Integer` |
| `virtualFlag` | `virtualFlag` | `Integer` |
| `status` | `status` | `Integer` |
| `clientStrictFlag` | `clientStrictFlag` | `Integer` |
| `createTimeStart` | `createTimeStart` | `Long` |
| `createTimeEnd` | `createTimeEnd` | `Long` |
| `updateTimeStart` | `updateTimeStart` | `Long` |
| `updateTimeEnd` | `updateTimeEnd` | `Long` |
| `nameLike` | `nameLike` | `String` |
| `descriptionLike` | `descriptionLike` | `String` |

### Model PointRecordEntity

源码：`point/jia-point-core/src/main/java/cn/jia/point/entity/PointRecordEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `jiacn` | `jiacn` | `String` |
| `type` | `type` | `Integer` |
| `chg` | `chg` | `Integer` |
| `remain` | `remain` | `Integer` |

### Model PointReferralEntity

源码：`point/jia-point-core/src/main/java/cn/jia/point/entity/PointReferralEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `referrer` | `referrer` | `String` |
| `referral` | `referral` | `String` |

### Model PointSignEntity

源码：`point/jia-point-core/src/main/java/cn/jia/point/entity/PointSignEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `jiacn` | `jiacn` | `String` |
| `address` | `address` | `String` |
| `latitude` | `latitude` | `String` |
| `longitude` | `longitude` | `String` |
| `point` | `point` | `Integer` |
