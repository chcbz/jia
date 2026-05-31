# Kefu 客服模块接口文档

来源模块：`kefu`

本文面向前端对接，每个接口包含请求方式、地址、参数位置、必填性、请求体字段和响应结构。

## 通用响应结构

- 单对象或操作结果：`JsonResult<T>`，字段：`code`、`msg`、`status`、`location`、`data`。
- 分页列表：`JsonResultPage<T>`，字段：`code`、`msg`、`status`、`pageNum`、`total`、`data`（数组）。
- 成功码默认为 `E0`，HTTP 状态通常为 `200`。

## 接口汇总

| 请求方式 | 地址 | Controller | 响应 |
| --- | --- | --- | --- |
| POST | `/kefu/faq/list` | `KefuController.listFaq` | JsonResultPage<[KefuFaqEntity](#model-kefufaqentity)> |
| GET | `/kefu/faq/get` | `KefuController.findFaqById` | JsonResult<[KefuFaqEntity](#model-kefufaqentity)> |
| POST | `/kefu/faq/create` | `KefuController.createFaq` | JsonResult<record> |
| POST | `/kefu/faq/update` | `KefuController.updateFaq` | JsonResult<record> |
| GET | `/kefu/faq/delete` | `KefuController.deleteFaq` | JsonResult<Void> |
| POST | `/kefu/message/list` | `KefuController.listMessage` | JsonResultPage<[KefuMessageEntity](#model-kefumessageentity)> |
| GET | `/kefu/message/get` | `KefuController.findMessageById` | JsonResult<[KefuMessageEntity](#model-kefumessageentity)> |
| POST | `/kefu/message/create` | `KefuController.createMessage` | JsonResult<record> |
| POST | `/kefu/message/update` | `KefuController.updateMessage` | JsonResult<record> |
| GET | `/kefu/message/delete` | `KefuController.deleteMessage` | JsonResult<Void> |
| POST | `/kefu/image/upload` | `KefuController.updateLogo` | JsonResult<[IspFileEntity](#model-ispfileentity)> |

## 接口明细

### KefuController `/kefu`

源码：`kefu/jia-kefu-service/src/main/java/cn/jia/kefu/api/KefuController.java`

#### POST `/kefu/faq/list`

- 处理方法：`KefuController.listFaq`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[KefuFaqEntity](#model-kefufaqentity)> | 请求体 |

请求体字段：

[KefuFaqEntity](#model-kefufaqentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `KefuFaqEntity` |
| `updateTime` | `Long` | 源自 `KefuFaqEntity` |
| `tenantId` | `String` | 源自 `KefuFaqEntity` |
| `clientId` | `String` | 源自 `KefuFaqEntity` |
| `id` | `Long` | 源自 `KefuFaqEntity` |
| `type` | `String` | 源自 `KefuFaqEntity` |
| `conversationId` | `String` | 源自 `KefuFaqEntity` |
| `title` | `String` | 源自 `KefuFaqEntity` |
| `content` | `String` | 源自 `KefuFaqEntity` |
| `click` | `Integer` | 源自 `KefuFaqEntity` |
| `useful` | `Integer` | 源自 `KefuFaqEntity` |
| `useless` | `Integer` | 源自 `KefuFaqEntity` |
| `status` | `Integer` | 源自 `KefuFaqEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/kefu/faq/get`

- 处理方法：`KefuController.findFaqById`
- 响应：[JsonResult](#model-jsonresult)<[KefuFaqEntity](#model-kefufaqentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<KefuFaqEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/kefu/faq/create`

- 处理方法：`KefuController.createFaq`
- 响应：[JsonResult](#model-jsonresult)<[KefuFaqEntity](#model-kefufaqentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `record` | body | yes | [KefuFaqEntity](#model-kefufaqentity) | 请求体 |

请求体字段：

[KefuFaqEntity](#model-kefufaqentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `KefuFaqEntity` |
| `updateTime` | `Long` | 源自 `KefuFaqEntity` |
| `tenantId` | `String` | 源自 `KefuFaqEntity` |
| `clientId` | `String` | 源自 `KefuFaqEntity` |
| `id` | `Long` | 源自 `KefuFaqEntity` |
| `type` | `String` | 源自 `KefuFaqEntity` |
| `conversationId` | `String` | 源自 `KefuFaqEntity` |
| `title` | `String` | 源自 `KefuFaqEntity` |
| `content` | `String` | 源自 `KefuFaqEntity` |
| `click` | `Integer` | 源自 `KefuFaqEntity` |
| `useful` | `Integer` | 源自 `KefuFaqEntity` |
| `useless` | `Integer` | 源自 `KefuFaqEntity` |
| `status` | `Integer` | 源自 `KefuFaqEntity` |

响应 data 说明：
- 按 `JsonResult<KefuFaqEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/kefu/faq/update`

- 处理方法：`KefuController.updateFaq`
- 响应：[JsonResult](#model-jsonresult)<[KefuFaqEntity](#model-kefufaqentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `record` | body | yes | [KefuFaqEntity](#model-kefufaqentity) | 请求体 |

请求体字段：

[KefuFaqEntity](#model-kefufaqentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `KefuFaqEntity` |
| `updateTime` | `Long` | 源自 `KefuFaqEntity` |
| `tenantId` | `String` | 源自 `KefuFaqEntity` |
| `clientId` | `String` | 源自 `KefuFaqEntity` |
| `id` | `Long` | 源自 `KefuFaqEntity` |
| `type` | `String` | 源自 `KefuFaqEntity` |
| `conversationId` | `String` | 源自 `KefuFaqEntity` |
| `title` | `String` | 源自 `KefuFaqEntity` |
| `content` | `String` | 源自 `KefuFaqEntity` |
| `click` | `Integer` | 源自 `KefuFaqEntity` |
| `useful` | `Integer` | 源自 `KefuFaqEntity` |
| `useless` | `Integer` | 源自 `KefuFaqEntity` |
| `status` | `Integer` | 源自 `KefuFaqEntity` |

响应 data 说明：
- 按 `JsonResult<KefuFaqEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/kefu/faq/delete`

- 处理方法：`KefuController.deleteFaq`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/kefu/message/list`

- 处理方法：`KefuController.listMessage`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[KefuMessageEntity](#model-kefumessageentity)> | 请求体 |

请求体字段：

[KefuMessageEntity](#model-kefumessageentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `KefuMessageEntity` |
| `updateTime` | `Long` | 源自 `KefuMessageEntity` |
| `tenantId` | `String` | 源自 `KefuMessageEntity` |
| `clientId` | `String` | 源自 `KefuMessageEntity` |
| `id` | `Long` | 源自 `KefuMessageEntity` |
| `jiacn` | `String` | 源自 `KefuMessageEntity` |
| `name` | `String` | 源自 `KefuMessageEntity` |
| `phone` | `String` | 源自 `KefuMessageEntity` |
| `email` | `String` | 源自 `KefuMessageEntity` |
| `title` | `String` | 源自 `KefuMessageEntity` |
| `content` | `String` | 源自 `KefuMessageEntity` |
| `attachment` | `String` | 源自 `KefuMessageEntity` |
| `reply` | `String` | 源自 `KefuMessageEntity` |
| `status` | `Integer` | 源自 `KefuMessageEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/kefu/message/get`

- 处理方法：`KefuController.findMessageById`
- 响应：[JsonResult](#model-jsonresult)<[KefuMessageEntity](#model-kefumessageentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<KefuMessageEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/kefu/message/create`

- 处理方法：`KefuController.createMessage`
- 响应：[JsonResult](#model-jsonresult)<[KefuMessageEntity](#model-kefumessageentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `attach` | multipart | no | MultipartFile | 文件/表单参数 |
| `record` | query/form | yes | [KefuMessageEntity](#model-kefumessageentity) | 查询/表单参数 |

请求体字段：

响应 data 说明：
- 按 `JsonResult<KefuMessageEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/kefu/message/update`

- 处理方法：`KefuController.updateMessage`
- 响应：[JsonResult](#model-jsonresult)<[KefuMessageEntity](#model-kefumessageentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `record` | body | yes | [KefuMessageEntity](#model-kefumessageentity) | 请求体 |

请求体字段：

[KefuMessageEntity](#model-kefumessageentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `KefuMessageEntity` |
| `updateTime` | `Long` | 源自 `KefuMessageEntity` |
| `tenantId` | `String` | 源自 `KefuMessageEntity` |
| `clientId` | `String` | 源自 `KefuMessageEntity` |
| `id` | `Long` | 源自 `KefuMessageEntity` |
| `jiacn` | `String` | 源自 `KefuMessageEntity` |
| `name` | `String` | 源自 `KefuMessageEntity` |
| `phone` | `String` | 源自 `KefuMessageEntity` |
| `email` | `String` | 源自 `KefuMessageEntity` |
| `title` | `String` | 源自 `KefuMessageEntity` |
| `content` | `String` | 源自 `KefuMessageEntity` |
| `attachment` | `String` | 源自 `KefuMessageEntity` |
| `reply` | `String` | 源自 `KefuMessageEntity` |
| `status` | `Integer` | 源自 `KefuMessageEntity` |

响应 data 说明：
- 按 `JsonResult<KefuMessageEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/kefu/message/delete`

- 处理方法：`KefuController.deleteMessage`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/kefu/image/upload`

- 处理方法：`KefuController.updateLogo`
- 响应：[JsonResult](#model-jsonresult)<[IspFileEntity](#model-ispfileentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `file` | multipart | yes | MultipartFile | 文件/表单参数 |

请求体字段：

响应 data 说明：
- 按 `JsonResult<IspFileEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

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

### Model IspFileEntity

源码：`isp/jia-isp-core/src/main/java/cn/jia/isp/entity/IspFileEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `name` | `name` | `String` |
| `uri` | `uri` | `String` |
| `size` | `size` | `Long` |
| `type` | `type` | `Integer` |
| `extension` | `extension` | `String` |
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

### Model KefuFaqEntity

源码：`kefu/jia-kefu-core/src/main/java/cn/jia/kefu/entity/KefuFaqEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `type` | `type` | `String` |
| `conversationId` | `conversationId` | `String` |
| `title` | `title` | `String` |
| `content` | `content` | `String` |
| `click` | `click` | `Integer` |
| `useful` | `useful` | `Integer` |
| `useless` | `useless` | `Integer` |
| `status` | `status` | `Integer` |

### Model KefuMessageEntity

源码：`kefu/jia-kefu-core/src/main/java/cn/jia/kefu/entity/KefuMessageEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `jiacn` | `jiacn` | `String` |
| `name` | `name` | `String` |
| `phone` | `phone` | `String` |
| `email` | `email` | `String` |
| `title` | `title` | `String` |
| `content` | `content` | `String` |
| `attachment` | `attachment` | `String` |
| `reply` | `reply` | `String` |
| `status` | `status` | `Integer` |
