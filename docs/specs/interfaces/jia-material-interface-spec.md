# Material 素材模块接口文档

来源模块：`material`

本文面向前端对接，每个接口包含请求方式、地址、参数位置、必填性、请求体字段和响应结构。

## 通用响应结构

- 单对象或操作结果：`JsonResult<T>`，字段：`code`、`msg`、`status`、`location`、`data`。
- 分页列表：`JsonResultPage<T>`，字段：`code`、`msg`、`status`、`pageNum`、`total`、`data`（数组）。
- 成功码默认为 `E0`，HTTP 状态通常为 `200`。

## 接口汇总

| 请求方式 | 地址 | Controller | 响应 |
| --- | --- | --- | --- |
| GET | `/media/get` | `MediaController.findById` | JsonResult<[MatMediaEntity](#model-matmediaentity)> |
| GET | `/media/get/content` | `MediaController.findContentById` | JsonResult<String> |
| POST | `/media/create` | `MediaController.create` | JsonResult<[MatMediaEntity](#model-matmediaentity)> |
| POST | `/media/update` | `MediaController.update` | JsonResult<media> |
| GET | `/media/delete` | `MediaController.delete` | JsonResult<Void> |
| POST | `/media/list` | `MediaController.list` | JsonResultPage<[MatMediaResVO](#model-matmediaresvo)> |
| POST | `/media/upload` | `MediaController.upload` | JsonResult<media> |
| GET | `/media/thumbnail` | `MediaController.thumbnail` | Object |
| GET | `/news/get` | `NewsController.findById` | JsonResult<[MatNewsEntity](#model-matnewsentity)> |
| POST | `/news/create` | `NewsController.create` | JsonResult<news> |
| POST | `/news/update` | `NewsController.update` | JsonResult<Void> |
| GET | `/news/delete` | `NewsController.delete` | JsonResult<Void> |
| POST | `/news/list` | `NewsController.list` | JsonResultPage<[MatNewsEntity](#model-matnewsentity)> |
| GET | `/news/send` | `NewsController.send` | JsonResult<Void> |
| GET | `/phrase/get` | `PhraseController.findById` | JsonResult<[MatPhraseEntity](#model-matphraseentity)> |
| POST | `/phrase/create` | `PhraseController.create` | JsonResult<Void> |
| POST | `/phrase/update` | `PhraseController.update` | JsonResult<Void> |
| GET | `/phrase/delete` | `PhraseController.delete` | JsonResult<Void> |
| POST | `/phrase/get/random` | `PhraseController.getRandom` | JsonResult<[MatPhraseEntity](#model-matphraseentity)> |
| POST | `/phrase/vote` | `PhraseController.vote` | JsonResult<Void> |
| GET | `/phrase/read` | `PhraseController.read` | JsonResult<Void> |
| GET | `/pvlog/get` | `PvLogController.findById` | JsonResult<[MatPvLogEntity](#model-matpvlogentity)> |
| POST | `/pvlog/create` | `PvLogController.create` | JsonResult<pageViewLog> |
| POST | `/pvlog/update` | `PvLogController.update` | JsonResult<Void> |
| DELETE | `/pvlog/delete` | `PvLogController.delete` | JsonResult<Void> |
| POST | `/pvlog/list` | `PvLogController.list` | JsonResultPage<[MatPvLogEntity](#model-matpvlogentity)> |
| GET | `/tip/get` | `TipController.findById` | JsonResult<[MatTipEntity](#model-mattipentity)> |
| POST | `/tip/create` | `TipController.create` | JsonResult<tip> |
| POST | `/tip/update` | `TipController.update` | JsonResult<tip> |
| GET | `/tip/delete` | `TipController.delete` | JsonResult<Void> |
| POST | `/tip/list` | `TipController.list` | JsonResultPage<[MatTipEntity](#model-mattipentity)> |
| GET | `/vote/get` | `VoteController.findById` | JsonResult<[MatVoteEntity](#model-matvoteentity)> |
| POST | `/vote/create` | `VoteController.create` | JsonResult<vote> |
| POST | `/vote/update` | `VoteController.update` | JsonResult<Void> |
| GET | `/vote/delete` | `VoteController.delete` | JsonResult<Void> |
| POST | `/vote/list` | `VoteController.list` | JsonResultPage<[MatVoteEntity](#model-matvoteentity)> |
| POST | `/vote/get/ticks` | `VoteController.findTicks` | JsonResult<List<[MatVoteTickEntity](#model-matvotetickentity)>> |
| GET | `/vote/get/random` | `VoteController.findRandom` | JsonResult<[MatVoteQuestionVO](#model-matvotequestionvo)> |
| POST | `/vote/tick` | `VoteController.tick` | JsonResult<tick> |

## 接口明细

### MediaController `/media`

源码：`material/jia-material-service/src/main/java/cn/jia/mat/api/MediaController.java`

#### GET `/media/get`

- 处理方法：`MediaController.findById`
- 响应：[JsonResult](#model-jsonresult)<[MatMediaEntity](#model-matmediaentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<MatMediaEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/media/get/content`

- 处理方法：`MediaController.findContentById`
- 响应：[JsonResult](#model-jsonresult)<String>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<String>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/media/create`

- 处理方法：`MediaController.create`
- 响应：[JsonResult](#model-jsonresult)<[MatMediaEntity](#model-matmediaentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `media` | body | yes | [MatMediaEntity](#model-matmediaentity) | 请求体 |

请求体字段：

[MatMediaEntity](#model-matmediaentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `MatMediaEntity` |
| `updateTime` | `Long` | 源自 `MatMediaEntity` |
| `tenantId` | `String` | 源自 `MatMediaEntity` |
| `clientId` | `String` | 源自 `MatMediaEntity` |
| `id` | `Long` | 源自 `MatMediaEntity` |
| `title` | `String` | 源自 `MatMediaEntity` |
| `type` | `Integer` | 源自 `MatMediaEntity` |
| `url` | `String` | 源自 `MatMediaEntity` |
| `entityId` | `Long` | 源自 `MatMediaEntity` |

响应 data 说明：
- 按 `JsonResult<MatMediaEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/media/update`

- 处理方法：`MediaController.update`
- 响应：[JsonResult](#model-jsonresult)<[MatMediaEntity](#model-matmediaentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `media` | body | yes | [MatMediaEntity](#model-matmediaentity) | 请求体 |

请求体字段：

[MatMediaEntity](#model-matmediaentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `MatMediaEntity` |
| `updateTime` | `Long` | 源自 `MatMediaEntity` |
| `tenantId` | `String` | 源自 `MatMediaEntity` |
| `clientId` | `String` | 源自 `MatMediaEntity` |
| `id` | `Long` | 源自 `MatMediaEntity` |
| `title` | `String` | 源自 `MatMediaEntity` |
| `type` | `Integer` | 源自 `MatMediaEntity` |
| `url` | `String` | 源自 `MatMediaEntity` |
| `entityId` | `Long` | 源自 `MatMediaEntity` |

响应 data 说明：
- 按 `JsonResult<MatMediaEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/media/delete`

- 处理方法：`MediaController.delete`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/media/list`

- 处理方法：`MediaController.list`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[MatMediaReqVO](#model-matmediareqvo)> | 请求体 |

请求体字段：

[MatMediaReqVO](#model-matmediareqvo)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `MatMediaReqVO` |
| `updateTime` | `Long` | 源自 `MatMediaReqVO` |
| `tenantId` | `String` | 源自 `MatMediaReqVO` |
| `clientId` | `String` | 源自 `MatMediaReqVO` |
| `id` | `Long` | 源自 `MatMediaReqVO` |
| `title` | `String` | 源自 `MatMediaReqVO` |
| `type` | `Integer` | 源自 `MatMediaReqVO` |
| `url` | `String` | 源自 `MatMediaReqVO` |
| `entityId` | `Long` | 源自 `MatMediaReqVO` |
| `clientStrictFlag` | `Integer` | 源自 `MatMediaReqVO` |
| `timeStart` | `Long` | 源自 `MatMediaReqVO` |
| `timeEnd` | `Long` | 源自 `MatMediaReqVO` |
| `titleLike` | `String` | 源自 `MatMediaReqVO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/media/upload`

- 处理方法：`MediaController.upload`
- 响应：[JsonResult](#model-jsonresult)<[MatMediaResVO](#model-matmediaresvo)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `file` | multipart | no | MultipartFile | 文件/表单参数 |
| `media` | query/form | yes | [MatMediaResVO](#model-matmediaresvo) | 查询/表单参数 |

请求体字段：

响应 data 说明：
- 按 `JsonResult<MatMediaResVO>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/media/thumbnail`

- 处理方法：`MediaController.thumbnail`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

### NewsController `/news`

源码：`material/jia-material-service/src/main/java/cn/jia/mat/api/NewsController.java`

#### GET `/news/get`

- 处理方法：`NewsController.findById`
- 响应：[JsonResult](#model-jsonresult)<[MatNewsEntity](#model-matnewsentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<MatNewsEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/news/create`

- 处理方法：`NewsController.create`
- 响应：[JsonResult](#model-jsonresult)<[MatNewsEntity](#model-matnewsentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `news` | body | yes | [MatNewsEntity](#model-matnewsentity) | 请求体 |

请求体字段：

[MatNewsEntity](#model-matnewsentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `MatNewsEntity` |
| `updateTime` | `Long` | 源自 `MatNewsEntity` |
| `tenantId` | `String` | 源自 `MatNewsEntity` |
| `clientId` | `String` | 源自 `MatNewsEntity` |
| `id` | `Long` | 源自 `MatNewsEntity` |
| `title` | `String` | 源自 `MatNewsEntity` |
| `author` | `String` | 源自 `MatNewsEntity` |
| `digest` | `String` | 源自 `MatNewsEntity` |
| `bodyurl` | `String` | 源自 `MatNewsEntity` |
| `picurl` | `String` | 源自 `MatNewsEntity` |
| `entityId` | `Long` | 源自 `MatNewsEntity` |

响应 data 说明：
- 按 `JsonResult<MatNewsEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/news/update`

- 处理方法：`NewsController.update`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `news` | body | yes | [MatNewsEntity](#model-matnewsentity) | 请求体 |

请求体字段：

[MatNewsEntity](#model-matnewsentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `MatNewsEntity` |
| `updateTime` | `Long` | 源自 `MatNewsEntity` |
| `tenantId` | `String` | 源自 `MatNewsEntity` |
| `clientId` | `String` | 源自 `MatNewsEntity` |
| `id` | `Long` | 源自 `MatNewsEntity` |
| `title` | `String` | 源自 `MatNewsEntity` |
| `author` | `String` | 源自 `MatNewsEntity` |
| `digest` | `String` | 源自 `MatNewsEntity` |
| `bodyurl` | `String` | 源自 `MatNewsEntity` |
| `picurl` | `String` | 源自 `MatNewsEntity` |
| `entityId` | `Long` | 源自 `MatNewsEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/news/delete`

- 处理方法：`NewsController.delete`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/news/list`

- 处理方法：`NewsController.list`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[MatNewsReqVO](#model-matnewsreqvo)> | 请求体 |

请求体字段：

[MatNewsReqVO](#model-matnewsreqvo)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `MatNewsReqVO` |
| `updateTime` | `Long` | 源自 `MatNewsReqVO` |
| `tenantId` | `String` | 源自 `MatNewsReqVO` |
| `clientId` | `String` | 源自 `MatNewsReqVO` |
| `id` | `Long` | 源自 `MatNewsReqVO` |
| `title` | `String` | 源自 `MatNewsReqVO` |
| `author` | `String` | 源自 `MatNewsReqVO` |
| `digest` | `String` | 源自 `MatNewsReqVO` |
| `bodyurl` | `String` | 源自 `MatNewsReqVO` |
| `picurl` | `String` | 源自 `MatNewsReqVO` |
| `entityId` | `Long` | 源自 `MatNewsReqVO` |
| `clientStrictFlag` | `Integer` | 源自 `MatNewsReqVO` |
| `createTimeStart` | `Long` | 源自 `MatNewsReqVO` |
| `createTimeEnd` | `Long` | 源自 `MatNewsReqVO` |
| `updateTimeStart` | `Long` | 源自 `MatNewsReqVO` |
| `updateTimeEnd` | `Long` | 源自 `MatNewsReqVO` |
| `titleLike` | `String` | 源自 `MatNewsReqVO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/news/send`

- 处理方法：`NewsController.send`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `jiacn` | query | yes | String | 查询/表单参数 |
| `type` | query | yes | Integer | 查询/表单参数 |
| `id` | query | yes | Long | 查询/表单参数 |
| `wxappid` | query | no | String | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

### PhraseController `/phrase`

源码：`material/jia-material-service/src/main/java/cn/jia/mat/api/PhraseController.java`

#### GET `/phrase/get`

- 处理方法：`PhraseController.findById`
- 响应：[JsonResult](#model-jsonresult)<[MatPhraseEntity](#model-matphraseentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<MatPhraseEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/phrase/create`

- 处理方法：`PhraseController.create`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `phrase` | body | yes | [MatPhraseEntity](#model-matphraseentity) | 请求体 |

请求体字段：

[MatPhraseEntity](#model-matphraseentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `MatPhraseEntity` |
| `updateTime` | `Long` | 源自 `MatPhraseEntity` |
| `tenantId` | `String` | 源自 `MatPhraseEntity` |
| `clientId` | `String` | 源自 `MatPhraseEntity` |
| `id` | `Long` | 源自 `MatPhraseEntity` |
| `content` | `String` | 源自 `MatPhraseEntity` |
| `tag` | `String` | 源自 `MatPhraseEntity` |
| `status` | `Integer` | 源自 `MatPhraseEntity` |
| `pv` | `Integer` | 源自 `MatPhraseEntity` |
| `up` | `Integer` | 源自 `MatPhraseEntity` |
| `down` | `Integer` | 源自 `MatPhraseEntity` |
| `jiacn` | `String` | 源自 `MatPhraseEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/phrase/update`

- 处理方法：`PhraseController.update`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `phrase` | body | yes | [MatPhraseEntity](#model-matphraseentity) | 请求体 |

请求体字段：

[MatPhraseEntity](#model-matphraseentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `MatPhraseEntity` |
| `updateTime` | `Long` | 源自 `MatPhraseEntity` |
| `tenantId` | `String` | 源自 `MatPhraseEntity` |
| `clientId` | `String` | 源自 `MatPhraseEntity` |
| `id` | `Long` | 源自 `MatPhraseEntity` |
| `content` | `String` | 源自 `MatPhraseEntity` |
| `tag` | `String` | 源自 `MatPhraseEntity` |
| `status` | `Integer` | 源自 `MatPhraseEntity` |
| `pv` | `Integer` | 源自 `MatPhraseEntity` |
| `up` | `Integer` | 源自 `MatPhraseEntity` |
| `down` | `Integer` | 源自 `MatPhraseEntity` |
| `jiacn` | `String` | 源自 `MatPhraseEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/phrase/delete`

- 处理方法：`PhraseController.delete`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/phrase/get/random`

- 处理方法：`PhraseController.getRandom`
- 响应：[JsonResult](#model-jsonresult)<[MatPhraseEntity](#model-matphraseentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `example` | body | yes | [MatPhraseEntity](#model-matphraseentity) | 请求体 |

请求体字段：

[MatPhraseEntity](#model-matphraseentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `MatPhraseEntity` |
| `updateTime` | `Long` | 源自 `MatPhraseEntity` |
| `tenantId` | `String` | 源自 `MatPhraseEntity` |
| `clientId` | `String` | 源自 `MatPhraseEntity` |
| `id` | `Long` | 源自 `MatPhraseEntity` |
| `content` | `String` | 源自 `MatPhraseEntity` |
| `tag` | `String` | 源自 `MatPhraseEntity` |
| `status` | `Integer` | 源自 `MatPhraseEntity` |
| `pv` | `Integer` | 源自 `MatPhraseEntity` |
| `up` | `Integer` | 源自 `MatPhraseEntity` |
| `down` | `Integer` | 源自 `MatPhraseEntity` |
| `jiacn` | `String` | 源自 `MatPhraseEntity` |

响应 data 说明：
- 按 `JsonResult<MatPhraseEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/phrase/vote`

- 处理方法：`PhraseController.vote`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `vote` | body | yes | [MatPhraseVoteEntity](#model-matphrasevoteentity) | 请求体 |

请求体字段：

[MatPhraseVoteEntity](#model-matphrasevoteentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `MatPhraseVoteEntity` |
| `updateTime` | `Long` | 源自 `MatPhraseVoteEntity` |
| `tenantId` | `String` | 源自 `MatPhraseVoteEntity` |
| `clientId` | `String` | 源自 `MatPhraseVoteEntity` |
| `id` | `Long` | 源自 `MatPhraseVoteEntity` |
| `jiacn` | `String` | 源自 `MatPhraseVoteEntity` |
| `phraseId` | `Long` | 源自 `MatPhraseVoteEntity` |
| `vote` | `Integer` | 源自 `MatPhraseVoteEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/phrase/read`

- 处理方法：`PhraseController.read`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

### PvLogController `/pvlog`

源码：`material/jia-material-service/src/main/java/cn/jia/mat/api/PvLogController.java`

#### GET `/pvlog/get`

- 处理方法：`PvLogController.findById`
- 响应：[JsonResult](#model-jsonresult)<[MatPvLogEntity](#model-matpvlogentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<MatPvLogEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/pvlog/create`

- 处理方法：`PvLogController.create`
- 响应：[JsonResult](#model-jsonresult)<[MatPvLogEntity](#model-matpvlogentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `pageViewLog` | body | yes | [MatPvLogEntity](#model-matpvlogentity) | 请求体 |

请求体字段：

[MatPvLogEntity](#model-matpvlogentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `MatPvLogEntity` |
| `updateTime` | `Long` | 源自 `MatPvLogEntity` |
| `tenantId` | `String` | 源自 `MatPvLogEntity` |
| `clientId` | `String` | 源自 `MatPvLogEntity` |
| `id` | `Long` | 源自 `MatPvLogEntity` |
| `entityId` | `Long` | 源自 `MatPvLogEntity` |
| `jiacn` | `String` | 源自 `MatPvLogEntity` |
| `phone` | `String` | 源自 `MatPvLogEntity` |
| `email` | `String` | 源自 `MatPvLogEntity` |
| `sex` | `Integer` | 源自 `MatPvLogEntity` |
| `nickname` | `String` | 源自 `MatPvLogEntity` |
| `avatar` | `String` | 源自 `MatPvLogEntity` |
| `city` | `String` | 源自 `MatPvLogEntity` |
| `country` | `String` | 源自 `MatPvLogEntity` |
| `province` | `String` | 源自 `MatPvLogEntity` |
| `latitude` | `String` | 源自 `MatPvLogEntity` |
| `longitude` | `String` | 源自 `MatPvLogEntity` |
| `ip` | `String` | 源自 `MatPvLogEntity` |
| `uri` | `String` | 源自 `MatPvLogEntity` |
| `userAgent` | `String` | 源自 `MatPvLogEntity` |

响应 data 说明：
- 按 `JsonResult<MatPvLogEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/pvlog/update`

- 处理方法：`PvLogController.update`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `pageViewLog` | body | yes | [MatPvLogEntity](#model-matpvlogentity) | 请求体 |

请求体字段：

[MatPvLogEntity](#model-matpvlogentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `MatPvLogEntity` |
| `updateTime` | `Long` | 源自 `MatPvLogEntity` |
| `tenantId` | `String` | 源自 `MatPvLogEntity` |
| `clientId` | `String` | 源自 `MatPvLogEntity` |
| `id` | `Long` | 源自 `MatPvLogEntity` |
| `entityId` | `Long` | 源自 `MatPvLogEntity` |
| `jiacn` | `String` | 源自 `MatPvLogEntity` |
| `phone` | `String` | 源自 `MatPvLogEntity` |
| `email` | `String` | 源自 `MatPvLogEntity` |
| `sex` | `Integer` | 源自 `MatPvLogEntity` |
| `nickname` | `String` | 源自 `MatPvLogEntity` |
| `avatar` | `String` | 源自 `MatPvLogEntity` |
| `city` | `String` | 源自 `MatPvLogEntity` |
| `country` | `String` | 源自 `MatPvLogEntity` |
| `province` | `String` | 源自 `MatPvLogEntity` |
| `latitude` | `String` | 源自 `MatPvLogEntity` |
| `longitude` | `String` | 源自 `MatPvLogEntity` |
| `ip` | `String` | 源自 `MatPvLogEntity` |
| `uri` | `String` | 源自 `MatPvLogEntity` |
| `userAgent` | `String` | 源自 `MatPvLogEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### DELETE `/pvlog/delete`

- 处理方法：`PvLogController.delete`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/pvlog/list`

- 处理方法：`PvLogController.list`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[MatPvLogEntity](#model-matpvlogentity)> | 请求体 |

请求体字段：

[MatPvLogEntity](#model-matpvlogentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `MatPvLogEntity` |
| `updateTime` | `Long` | 源自 `MatPvLogEntity` |
| `tenantId` | `String` | 源自 `MatPvLogEntity` |
| `clientId` | `String` | 源自 `MatPvLogEntity` |
| `id` | `Long` | 源自 `MatPvLogEntity` |
| `entityId` | `Long` | 源自 `MatPvLogEntity` |
| `jiacn` | `String` | 源自 `MatPvLogEntity` |
| `phone` | `String` | 源自 `MatPvLogEntity` |
| `email` | `String` | 源自 `MatPvLogEntity` |
| `sex` | `Integer` | 源自 `MatPvLogEntity` |
| `nickname` | `String` | 源自 `MatPvLogEntity` |
| `avatar` | `String` | 源自 `MatPvLogEntity` |
| `city` | `String` | 源自 `MatPvLogEntity` |
| `country` | `String` | 源自 `MatPvLogEntity` |
| `province` | `String` | 源自 `MatPvLogEntity` |
| `latitude` | `String` | 源自 `MatPvLogEntity` |
| `longitude` | `String` | 源自 `MatPvLogEntity` |
| `ip` | `String` | 源自 `MatPvLogEntity` |
| `uri` | `String` | 源自 `MatPvLogEntity` |
| `userAgent` | `String` | 源自 `MatPvLogEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

### TipController `/tip`

源码：`material/jia-material-service/src/main/java/cn/jia/mat/api/TipController.java`

#### GET `/tip/get`

- 处理方法：`TipController.findById`
- 响应：[JsonResult](#model-jsonresult)<[MatTipEntity](#model-mattipentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<MatTipEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/tip/create`

- 处理方法：`TipController.create`
- 响应：[JsonResult](#model-jsonresult)<[MatTipEntity](#model-mattipentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `tip` | body | yes | [MatTipEntity](#model-mattipentity) | 请求体 |

请求体字段：

[MatTipEntity](#model-mattipentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `MatTipEntity` |
| `updateTime` | `Long` | 源自 `MatTipEntity` |
| `tenantId` | `String` | 源自 `MatTipEntity` |
| `clientId` | `String` | 源自 `MatTipEntity` |
| `id` | `Long` | 源自 `MatTipEntity` |
| `type` | `Integer` | 源自 `MatTipEntity` |
| `entityId` | `Long` | 源自 `MatTipEntity` |
| `jiacn` | `String` | 源自 `MatTipEntity` |
| `price` | `Integer` | 源自 `MatTipEntity` |
| `status` | `Integer` | 源自 `MatTipEntity` |

响应 data 说明：
- 按 `JsonResult<MatTipEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/tip/update`

- 处理方法：`TipController.update`
- 响应：[JsonResult](#model-jsonresult)<[MatTipEntity](#model-mattipentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `tip` | body | yes | [MatTipEntity](#model-mattipentity) | 请求体 |

请求体字段：

[MatTipEntity](#model-mattipentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `MatTipEntity` |
| `updateTime` | `Long` | 源自 `MatTipEntity` |
| `tenantId` | `String` | 源自 `MatTipEntity` |
| `clientId` | `String` | 源自 `MatTipEntity` |
| `id` | `Long` | 源自 `MatTipEntity` |
| `type` | `Integer` | 源自 `MatTipEntity` |
| `entityId` | `Long` | 源自 `MatTipEntity` |
| `jiacn` | `String` | 源自 `MatTipEntity` |
| `price` | `Integer` | 源自 `MatTipEntity` |
| `status` | `Integer` | 源自 `MatTipEntity` |

响应 data 说明：
- 按 `JsonResult<MatTipEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/tip/delete`

- 处理方法：`TipController.delete`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/tip/list`

- 处理方法：`TipController.list`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[MatTipEntity](#model-mattipentity)> | 请求体 |

请求体字段：

[MatTipEntity](#model-mattipentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `MatTipEntity` |
| `updateTime` | `Long` | 源自 `MatTipEntity` |
| `tenantId` | `String` | 源自 `MatTipEntity` |
| `clientId` | `String` | 源自 `MatTipEntity` |
| `id` | `Long` | 源自 `MatTipEntity` |
| `type` | `Integer` | 源自 `MatTipEntity` |
| `entityId` | `Long` | 源自 `MatTipEntity` |
| `jiacn` | `String` | 源自 `MatTipEntity` |
| `price` | `Integer` | 源自 `MatTipEntity` |
| `status` | `Integer` | 源自 `MatTipEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

### VoteController `/vote`

源码：`material/jia-material-service/src/main/java/cn/jia/mat/api/VoteController.java`

#### GET `/vote/get`

- 处理方法：`VoteController.findById`
- 响应：[JsonResult](#model-jsonresult)<[MatVoteEntity](#model-matvoteentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<MatVoteEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/vote/create`

- 处理方法：`VoteController.create`
- 响应：[JsonResult](#model-jsonresult)<[MatVoteResVO](#model-matvoteresvo)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `vote` | body | yes | [MatVoteResVO](#model-matvoteresvo) | 请求体 |

请求体字段：

[MatVoteResVO](#model-matvoteresvo)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `MatVoteResVO` |
| `updateTime` | `Long` | 源自 `MatVoteResVO` |
| `tenantId` | `String` | 源自 `MatVoteResVO` |
| `clientId` | `String` | 源自 `MatVoteResVO` |
| `id` | `Long` | 源自 `MatVoteResVO` |
| `name` | `String` | 源自 `MatVoteResVO` |
| `startTime` | `Long` | 源自 `MatVoteResVO` |
| `closeTime` | `Long` | 源自 `MatVoteResVO` |
| `num` | `Integer` | 源自 `MatVoteResVO` |
| `questions` | `List<MatVoteQuestionVO>` | 源自 `MatVoteResVO` |

响应 data 说明：
- 按 `JsonResult<MatVoteResVO>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/vote/update`

- 处理方法：`VoteController.update`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `vote` | body | yes | [MatVoteEntity](#model-matvoteentity) | 请求体 |

请求体字段：

[MatVoteEntity](#model-matvoteentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `MatVoteEntity` |
| `updateTime` | `Long` | 源自 `MatVoteEntity` |
| `tenantId` | `String` | 源自 `MatVoteEntity` |
| `clientId` | `String` | 源自 `MatVoteEntity` |
| `id` | `Long` | 源自 `MatVoteEntity` |
| `name` | `String` | 源自 `MatVoteEntity` |
| `startTime` | `Long` | 源自 `MatVoteEntity` |
| `closeTime` | `Long` | 源自 `MatVoteEntity` |
| `num` | `Integer` | 源自 `MatVoteEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/vote/delete`

- 处理方法：`VoteController.delete`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/vote/list`

- 处理方法：`VoteController.list`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[MatVoteReqVO](#model-matvotereqvo)> | 请求体 |

请求体字段：

[MatVoteReqVO](#model-matvotereqvo)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `MatVoteReqVO` |
| `updateTime` | `Long` | 源自 `MatVoteReqVO` |
| `tenantId` | `String` | 源自 `MatVoteReqVO` |
| `clientId` | `String` | 源自 `MatVoteReqVO` |
| `id` | `Long` | 源自 `MatVoteReqVO` |
| `name` | `String` | 源自 `MatVoteReqVO` |
| `startTime` | `Long` | 源自 `MatVoteReqVO` |
| `closeTime` | `Long` | 源自 `MatVoteReqVO` |
| `num` | `Integer` | 源自 `MatVoteReqVO` |
| `nameLike` | `String` | 源自 `MatVoteReqVO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/vote/get/ticks`

- 处理方法：`VoteController.findTicks`
- 响应：[JsonResult](#model-jsonresult)<List<[MatVoteTickEntity](#model-matvotetickentity)>>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `voteTick` | body | yes | [MatVoteTickEntity](#model-matvotetickentity) | 请求体 |

请求体字段：

[MatVoteTickEntity](#model-matvotetickentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `MatVoteTickEntity` |
| `updateTime` | `Long` | 源自 `MatVoteTickEntity` |
| `tenantId` | `String` | 源自 `MatVoteTickEntity` |
| `clientId` | `String` | 源自 `MatVoteTickEntity` |
| `id` | `Long` | 源自 `MatVoteTickEntity` |
| `jiacn` | `String` | 源自 `MatVoteTickEntity` |
| `voteId` | `Long` | 源自 `MatVoteTickEntity` |
| `questionId` | `Long` | 源自 `MatVoteTickEntity` |
| `opt` | `String` | 源自 `MatVoteTickEntity` |
| `tick` | `Integer` | 源自 `MatVoteTickEntity` |

响应 data 说明：
- 按 `JsonResult<List<MatVoteTickEntity>>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/vote/get/random`

- 处理方法：`VoteController.findRandom`
- 响应：[JsonResult](#model-jsonresult)<[MatVoteQuestionVO](#model-matvotequestionvo)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `jiacn` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<MatVoteQuestionVO>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/vote/tick`

- 处理方法：`VoteController.tick`
- 响应：[JsonResult](#model-jsonresult)<expression: tick>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `voteTick` | body | yes | [MatVoteTickEntity](#model-matvotetickentity) | 请求体 |

请求体字段：

[MatVoteTickEntity](#model-matvotetickentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `MatVoteTickEntity` |
| `updateTime` | `Long` | 源自 `MatVoteTickEntity` |
| `tenantId` | `String` | 源自 `MatVoteTickEntity` |
| `clientId` | `String` | 源自 `MatVoteTickEntity` |
| `id` | `Long` | 源自 `MatVoteTickEntity` |
| `jiacn` | `String` | 源自 `MatVoteTickEntity` |
| `voteId` | `Long` | 源自 `MatVoteTickEntity` |
| `questionId` | `Long` | 源自 `MatVoteTickEntity` |
| `opt` | `String` | 源自 `MatVoteTickEntity` |
| `tick` | `Integer` | 源自 `MatVoteTickEntity` |

响应 data 说明：
- 按 `JsonResult<expression: tick>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

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

### Model MatMediaEntity

源码：`material/jia-material-core/src/main/java/cn/jia/mat/entity/MatMediaEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `title` | `title` | `String` |
| `type` | `type` | `Integer` |
| `url` | `url` | `String` |
| `entityId` | `entityId` | `Long` |

### Model MatMediaReqVO

源码：`material/jia-material-api/src/main/java/cn/jia/mat/entity/MatMediaReqVO.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `title` | `title` | `String` |
| `type` | `type` | `Integer` |
| `url` | `url` | `String` |
| `entityId` | `entityId` | `Long` |
| `clientStrictFlag` | `clientStrictFlag` | `Integer` |
| `timeStart` | `timeStart` | `Long` |
| `timeEnd` | `timeEnd` | `Long` |
| `titleLike` | `titleLike` | `String` |

### Model MatMediaResVO

源码：`material/jia-material-api/src/main/java/cn/jia/mat/entity/MatMediaResVO.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `title` | `title` | `String` |
| `type` | `type` | `Integer` |
| `url` | `url` | `String` |
| `entityId` | `entityId` | `Long` |
| `link` | `link` | `String` |
| `content` | `content` | `String` |

### Model MatNewsEntity

源码：`material/jia-material-core/src/main/java/cn/jia/mat/entity/MatNewsEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `title` | `title` | `String` |
| `author` | `author` | `String` |
| `digest` | `digest` | `String` |
| `bodyurl` | `bodyurl` | `String` |
| `picurl` | `picurl` | `String` |
| `entityId` | `entityId` | `Long` |

### Model MatNewsReqVO

源码：`material/jia-material-api/src/main/java/cn/jia/mat/entity/MatNewsReqVO.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `title` | `title` | `String` |
| `author` | `author` | `String` |
| `digest` | `digest` | `String` |
| `bodyurl` | `bodyurl` | `String` |
| `picurl` | `picurl` | `String` |
| `entityId` | `entityId` | `Long` |
| `clientStrictFlag` | `clientStrictFlag` | `Integer` |
| `createTimeStart` | `createTimeStart` | `Long` |
| `createTimeEnd` | `createTimeEnd` | `Long` |
| `updateTimeStart` | `updateTimeStart` | `Long` |
| `updateTimeEnd` | `updateTimeEnd` | `Long` |
| `titleLike` | `titleLike` | `String` |

### Model MatPhraseEntity

源码：`material/jia-material-core/src/main/java/cn/jia/mat/entity/MatPhraseEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `content` | `content` | `String` |
| `tag` | `tag` | `String` |
| `status` | `status` | `Integer` |
| `pv` | `pv` | `Integer` |
| `up` | `up` | `Integer` |
| `down` | `down` | `Integer` |
| `jiacn` | `jiacn` | `String` |

### Model MatPhraseVoteEntity

源码：`material/jia-material-core/src/main/java/cn/jia/mat/entity/MatPhraseVoteEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `jiacn` | `jiacn` | `String` |
| `phraseId` | `phraseId` | `Long` |
| `vote` | `vote` | `Integer` |

### Model MatPvLogEntity

源码：`material/jia-material-core/src/main/java/cn/jia/mat/entity/MatPvLogEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `entityId` | `entityId` | `Long` |
| `jiacn` | `jiacn` | `String` |
| `phone` | `phone` | `String` |
| `email` | `email` | `String` |
| `sex` | `sex` | `Integer` |
| `nickname` | `nickname` | `String` |
| `avatar` | `avatar` | `String` |
| `city` | `city` | `String` |
| `country` | `country` | `String` |
| `province` | `province` | `String` |
| `latitude` | `latitude` | `String` |
| `longitude` | `longitude` | `String` |
| `ip` | `ip` | `String` |
| `uri` | `uri` | `String` |
| `userAgent` | `userAgent` | `String` |

### Model MatTipEntity

源码：`material/jia-material-core/src/main/java/cn/jia/mat/entity/MatTipEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `type` | `type` | `Integer` |
| `entityId` | `entityId` | `Long` |
| `jiacn` | `jiacn` | `String` |
| `price` | `price` | `Integer` |
| `status` | `status` | `Integer` |

### Model MatVoteEntity

源码：`material/jia-material-core/src/main/java/cn/jia/mat/entity/MatVoteEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `name` | `name` | `String` |
| `startTime` | `startTime` | `Long` |
| `closeTime` | `closeTime` | `Long` |
| `num` | `num` | `Integer` |

### Model MatVoteQuestionVO

源码：`material/jia-material-api/src/main/java/cn/jia/mat/entity/MatVoteQuestionVO.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `voteId` | `voteId` | `Long` |
| `title` | `title` | `String` |
| `multi` | `multi` | `Integer` |
| `point` | `point` | `Integer` |
| `opt` | `opt` | `String` |
| `items` | `items` | `List<MatVoteItemEntity>` |

### Model MatVoteReqVO

源码：`material/jia-material-api/src/main/java/cn/jia/mat/entity/MatVoteReqVO.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `name` | `name` | `String` |
| `startTime` | `startTime` | `Long` |
| `closeTime` | `closeTime` | `Long` |
| `num` | `num` | `Integer` |
| `nameLike` | `nameLike` | `String` |

### Model MatVoteResVO

源码：`material/jia-material-api/src/main/java/cn/jia/mat/entity/MatVoteResVO.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `name` | `name` | `String` |
| `startTime` | `startTime` | `Long` |
| `closeTime` | `closeTime` | `Long` |
| `num` | `num` | `Integer` |
| `questions` | `questions` | `List<MatVoteQuestionVO>` |

### Model MatVoteTickEntity

源码：`material/jia-material-core/src/main/java/cn/jia/mat/entity/MatVoteTickEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `jiacn` | `jiacn` | `String` |
| `voteId` | `voteId` | `Long` |
| `questionId` | `questionId` | `Long` |
| `opt` | `opt` | `String` |
| `tick` | `tick` | `Integer` |
