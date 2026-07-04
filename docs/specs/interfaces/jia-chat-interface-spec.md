# Chat 聊天模块接口文档

来源模块：`chat`

本文面向前端对接，每个接口包含请求方式、地址、参数位置、必填性、请求体字段和响应结构。

## 通用响应结构

- 单对象或操作结果：`JsonResult<T>`，字段：`code`、`msg`、`status`、`location`、`data`。
- 分页列表：`JsonResultPage<T>`，字段：`code`、`msg`、`status`、`pageNum`、`total`、`data`（数组）。
- 成功码默认为 `E0`，HTTP 状态通常为 `200`。

## 接口汇总

| 请求方式 | 地址 | Controller | 响应 |
| --- | --- | --- | --- |
| POST | `/chat/stream` | `ChatController.handleChat` | Flux<String> |
| POST | `/chat/stop_stream` | `ChatController.stopStream` | JsonResult<Void> |
| DELETE | `/chat/conversation/delete` | `ChatController.deleteConversation` | JsonResult<Void> |
| GET | `/chat/conversation/content` | `ChatController.getConversationContent` | JsonResult<chatConversationService.findByConversationId(id)> |
| POST | `/chat/conversation/list` | `ChatController.listConversations` | JsonResultPage<[ChatConversationEntity](#model-chatconversationentity)> |
| POST | `/chat/conversation/update` | `ChatController.updateConversation` | JsonResult<Void> |

## 接口明细

### ChatController `/chat`

源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java`

#### POST `/chat/stream`

- 处理方法：`ChatController.handleChat`
- 响应：Flux<String>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `chatMessage` | body | yes | [ChatMessageDTO](#model-chatmessagedto) | 请求体 |

请求体字段：

[ChatMessageDTO](#model-chatmessagedto)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `conversationId` | `String` | 源自 `ChatMessageDTO` |
| `content` | `String` | 源自 `ChatMessageDTO` |

响应 data 说明：
- 按 `Flux<String>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/chat/stop_stream`

- 处理方法：`ChatController.stopStream`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `chatMessage` | body | yes | [ChatMessageDTO](#model-chatmessagedto) | 请求体 |

请求体字段：

[ChatMessageDTO](#model-chatmessagedto)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `conversationId` | `String` | 源自 `ChatMessageDTO` |
| `content` | `String` | 源自 `ChatMessageDTO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### DELETE `/chat/conversation/delete`

- 处理方法：`ChatController.deleteConversation`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/chat/conversation/content`

- 处理方法：`ChatController.getConversationContent`
- 响应：[JsonResult](#model-jsonresult)<expression: chatConversationService.findByConversationId(id)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<expression: chatConversationService.findByConversationId(id)>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/chat/conversation/list`

- 处理方法：`ChatController.listConversations`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[ChatConversationEntity](#model-chatconversationentity)> | 请求体 |

请求体字段：

[ChatConversationEntity](#model-chatconversationentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `ChatConversationEntity` |
| `updateTime` | `Long` | 源自 `ChatConversationEntity` |
| `tenantId` | `String` | 源自 `ChatConversationEntity` |
| `clientId` | `String` | 源自 `ChatConversationEntity` |
| `id` | `Long` | 源自 `ChatConversationEntity` |
| `title` | `String` | 源自 `ChatConversationEntity` |
| `jiacn` | `String` | 源自 `ChatConversationEntity` |
| `status` | `Integer` | 源自 `ChatConversationEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/chat/conversation/update`

- 处理方法：`ChatController.updateConversation`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `entity` | body | yes | [ChatConversationEntity](#model-chatconversationentity) | 请求体 |

请求体字段：

[ChatConversationEntity](#model-chatconversationentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `ChatConversationEntity` |
| `updateTime` | `Long` | 源自 `ChatConversationEntity` |
| `tenantId` | `String` | 源自 `ChatConversationEntity` |
| `clientId` | `String` | 源自 `ChatConversationEntity` |
| `id` | `Long` | 源自 `ChatConversationEntity` |
| `title` | `String` | 源自 `ChatConversationEntity` |
| `jiacn` | `String` | 源自 `ChatConversationEntity` |
| `status` | `Integer` | 源自 `ChatConversationEntity` |

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

### Model ChatConversationEntity

源码：`chat/jia-chat-core/src/main/java/cn/jia/chat/entity/ChatConversationEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `title` | `title` | `String` |
| `jiacn` | `jiacn` | `String` |
| `status` | `status` | `Integer` |

### Model ChatMessageDTO

源码：`chat/jia-chat-service/src/main/java/cn/jia/chat/handler/dto/ChatMessageDTO.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `conversationId` | `conversationId` | `String` |
| `content` | `content` | `String` |

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
