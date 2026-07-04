# SMS 短信模块接口文档

来源模块：`sms`

本文面向前端对接，每个接口包含请求方式、地址、参数位置、必填性、请求体字段和响应结构。

## 通用响应结构

- 单对象或操作结果：`JsonResult<T>`，字段：`code`、`msg`、`status`、`location`、`data`。
- 分页列表：`JsonResultPage<T>`，字段：`code`、`msg`、`status`、`pageNum`、`total`、`data`（数组）。
- 成功码默认为 `E0`，HTTP 状态通常为 `200`。

## 接口汇总

| 请求方式 | 地址 | Controller | 响应 |
| --- | --- | --- | --- |
| GET | `/sms/validate` | `SmsController.validateSmsCode` | JsonResult<Void> |
| GET | `/sms/use` | `SmsController.useSmsCode` | JsonResult<code.getSmsCode()> |
| GET | `/sms/gen` | `SmsController.gen` | JsonResult<String> |
| POST | `/sms/send` | `SmsController.sendSms` | JsonResult<result.getMessage()> |
| POST | `/sms/send/list` | `SmsController.listSend` | JsonResultPage<[SmsSendEntity](#model-smssendentity)> |
| POST | `/sms/sendBatch` | `SmsController.sendSmsBatch` | JsonResult<result.getMessage()> |
| GET | `/sms/balance` | `SmsController.balance` | JsonResult<config.getRemain()> |
| GET | `/sms/receive` | `SmsController.receive` | JsonResult<Void> |
| POST | `/sms/reply/list` | `SmsController.listReply` | JsonResultPage<[SmsReplyEntity](#model-smsreplyentity)> |
| GET | `/sms/config/get` | `SmsController.findConfig` | JsonResult<[SmsConfigEntity](#model-smsconfigentity)> |
| POST | `/sms/config/update` | `SmsController.updateConfig` | JsonResult<config> |
| GET | `/sms/template/get` | `SmsController.findTemplateById` | JsonResult<[SmsTemplateEntity](#model-smstemplateentity)> |
| POST | `/sms/template/create` | `SmsController.createTemplate` | JsonResult<sms> |
| POST | `/sms/template/update` | `SmsController.updateTemplate` | JsonResult<sms> |
| GET | `/sms/template/delete` | `SmsController.deleteTemplate` | JsonResult<Void> |
| POST | `/sms/template/list` | `SmsController.listTemplate` | JsonResultPage<[SmsTemplateEntity](#model-smstemplateentity)> |
| GET | `/sms/buy` | `SmsController.buy` | JsonResult<Void> |

## 接口明细

### SmsController `/sms`

源码：`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java`

#### GET `/sms/validate`

- 处理方法：`SmsController.validateSmsCode`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `phone` | query | yes | String | 查询/表单参数 |
| `smsType` | query | yes | Integer | 查询/表单参数 |
| `smsCode` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/sms/use`

- 处理方法：`SmsController.useSmsCode`
- 响应：[JsonResult](#model-jsonresult)<expression: code.getSmsCode()>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `phone` | query | yes | String | 查询/表单参数 |
| `smsType` | query | yes | Integer | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<expression: code.getSmsCode()>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/sms/gen`

- 处理方法：`SmsController.gen`
- 响应：[JsonResult](#model-jsonresult)<String>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `phone` | query | yes | String | 查询/表单参数 |
| `smsType` | query | yes | Integer | 查询/表单参数 |
| `templateId` | query | no | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<String>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/sms/send`

- 处理方法：`SmsController.sendSms`
- 响应：[JsonResult](#model-jsonresult)<expression: result.getMessage()>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `mobile` | query | yes | String | 查询/表单参数 |
| `content` | query | yes | String | 查询/表单参数 |
| `xh` | query | no | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<expression: result.getMessage()>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/sms/send/list`

- 处理方法：`SmsController.listSend`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[SmsSendVO](#model-smssendvo)> | 请求体 |

请求体字段：

[SmsSendVO](#model-smssendvo)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `SmsSendVO` |
| `updateTime` | `Long` | 源自 `SmsSendVO` |
| `tenantId` | `String` | 源自 `SmsSendVO` |
| `clientId` | `String` | 源自 `SmsSendVO` |
| `mobile` | `String` | 源自 `SmsSendVO` |
| `content` | `String` | 源自 `SmsSendVO` |
| `xh` | `String` | 源自 `SmsSendVO` |
| `msgid` | `String` | 源自 `SmsSendVO` |
| `timeStart` | `Long` | 源自 `SmsSendVO` |
| `timeEnd` | `Long` | 源自 `SmsSendVO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/sms/sendBatch`

- 处理方法：`SmsController.sendSmsBatch`
- 响应：[JsonResult](#model-jsonresult)<expression: result.getMessage()>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `records` | body | yes | List<SmsBatchRecord> | 请求体 |

请求体字段：

响应 data 说明：
- 按 `JsonResult<expression: result.getMessage()>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/sms/balance`

- 处理方法：`SmsController.balance`
- 响应：[JsonResult](#model-jsonresult)<expression: config.getRemain()>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| - | - | - | - | 无入参 |

响应 data 说明：
- 按 `JsonResult<expression: config.getRemain()>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/sms/receive`

- 处理方法：`SmsController.receive`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `mobile` | query | yes | String | 查询/表单参数 |
| `content` | query | yes | String | 查询/表单参数 |
| `msgid` | query | yes | String | 查询/表单参数 |
| `xh` | query | no | String | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/sms/reply/list`

- 处理方法：`SmsController.listReply`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[SmsReplyEntity](#model-smsreplyentity)> | 请求体 |

请求体字段：

[SmsReplyEntity](#model-smsreplyentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `SmsReplyEntity` |
| `updateTime` | `Long` | 源自 `SmsReplyEntity` |
| `tenantId` | `String` | 源自 `SmsReplyEntity` |
| `clientId` | `String` | 源自 `SmsReplyEntity` |
| `id` | `Long` | 源自 `SmsReplyEntity` |
| `msgid` | `String` | 源自 `SmsReplyEntity` |
| `mobile` | `String` | 源自 `SmsReplyEntity` |
| `xh` | `String` | 源自 `SmsReplyEntity` |
| `content` | `String` | 源自 `SmsReplyEntity` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/sms/config/get`

- 处理方法：`SmsController.findConfig`
- 响应：[JsonResult](#model-jsonresult)<[SmsConfigEntity](#model-smsconfigentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| - | - | - | - | 无入参 |

响应 data 说明：
- 按 `JsonResult<SmsConfigEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/sms/config/update`

- 处理方法：`SmsController.updateConfig`
- 响应：[JsonResult](#model-jsonresult)<[SmsConfigEntity](#model-smsconfigentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `config` | body | yes | [SmsConfigEntity](#model-smsconfigentity) | 请求体 |

请求体字段：

[SmsConfigEntity](#model-smsconfigentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `SmsConfigEntity` |
| `updateTime` | `Long` | 源自 `SmsConfigEntity` |
| `tenantId` | `String` | 源自 `SmsConfigEntity` |
| `clientId` | `String` | 源自 `SmsConfigEntity` |
| `shortName` | `String` | 源自 `SmsConfigEntity` |
| `replyUrl` | `String` | 源自 `SmsConfigEntity` |
| `remain` | `Integer` | 源自 `SmsConfigEntity` |

响应 data 说明：
- 按 `JsonResult<SmsConfigEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/sms/template/get`

- 处理方法：`SmsController.findTemplateById`
- 响应：[JsonResult](#model-jsonresult)<[SmsTemplateEntity](#model-smstemplateentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `templateId` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<SmsTemplateEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/sms/template/create`

- 处理方法：`SmsController.createTemplate`
- 响应：[JsonResult](#model-jsonresult)<[SmsTemplateEntity](#model-smstemplateentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `sms` | body | yes | [SmsTemplateEntity](#model-smstemplateentity) | 请求体 |

请求体字段：

[SmsTemplateEntity](#model-smstemplateentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `SmsTemplateEntity` |
| `updateTime` | `Long` | 源自 `SmsTemplateEntity` |
| `tenantId` | `String` | 源自 `SmsTemplateEntity` |
| `clientId` | `String` | 源自 `SmsTemplateEntity` |
| `templateId` | `String` | 源自 `SmsTemplateEntity` |
| `name` | `String` | 源自 `SmsTemplateEntity` |
| `title` | `String` | 源自 `SmsTemplateEntity` |
| `content` | `String` | 源自 `SmsTemplateEntity` |
| `msgType` | `Integer` | 源自 `SmsTemplateEntity` |
| `type` | `Integer` | 源自 `SmsTemplateEntity` |
| `status` | `Integer` | 源自 `SmsTemplateEntity` |

响应 data 说明：
- 按 `JsonResult<SmsTemplateEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/sms/template/update`

- 处理方法：`SmsController.updateTemplate`
- 响应：[JsonResult](#model-jsonresult)<[SmsTemplateEntity](#model-smstemplateentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `sms` | body | yes | [SmsTemplateEntity](#model-smstemplateentity) | 请求体 |

请求体字段：

[SmsTemplateEntity](#model-smstemplateentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `SmsTemplateEntity` |
| `updateTime` | `Long` | 源自 `SmsTemplateEntity` |
| `tenantId` | `String` | 源自 `SmsTemplateEntity` |
| `clientId` | `String` | 源自 `SmsTemplateEntity` |
| `templateId` | `String` | 源自 `SmsTemplateEntity` |
| `name` | `String` | 源自 `SmsTemplateEntity` |
| `title` | `String` | 源自 `SmsTemplateEntity` |
| `content` | `String` | 源自 `SmsTemplateEntity` |
| `msgType` | `Integer` | 源自 `SmsTemplateEntity` |
| `type` | `Integer` | 源自 `SmsTemplateEntity` |
| `status` | `Integer` | 源自 `SmsTemplateEntity` |

响应 data 说明：
- 按 `JsonResult<SmsTemplateEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/sms/template/delete`

- 处理方法：`SmsController.deleteTemplate`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `templateId` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/sms/template/list`

- 处理方法：`SmsController.listTemplate`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[SmsTemplateVO](#model-smstemplatevo)> | 请求体 |

请求体字段：

[SmsTemplateVO](#model-smstemplatevo)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `SmsTemplateVO` |
| `updateTime` | `Long` | 源自 `SmsTemplateVO` |
| `tenantId` | `String` | 源自 `SmsTemplateVO` |
| `clientId` | `String` | 源自 `SmsTemplateVO` |
| `templateId` | `String` | 源自 `SmsTemplateVO` |
| `name` | `String` | 源自 `SmsTemplateVO` |
| `title` | `String` | 源自 `SmsTemplateVO` |
| `content` | `String` | 源自 `SmsTemplateVO` |
| `msgType` | `Integer` | 源自 `SmsTemplateVO` |
| `type` | `Integer` | 源自 `SmsTemplateVO` |
| `status` | `Integer` | 源自 `SmsTemplateVO` |
| `clientStrictFlag` | `Integer` | 源自 `SmsTemplateVO` |
| `createTimeStart` | `Long` | 源自 `SmsTemplateVO` |
| `createTimeEnd` | `Long` | 源自 `SmsTemplateVO` |
| `updateTimeStart` | `Long` | 源自 `SmsTemplateVO` |
| `updateTimeEnd` | `Long` | 源自 `SmsTemplateVO` |
| `nameLike` | `String` | 源自 `SmsTemplateVO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/sms/buy`

- 处理方法：`SmsController.buy`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `number` | query | yes | Integer | 查询/表单参数 |
| `money` | query | yes | Double | 查询/表单参数 |

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

### Model SmsConfigEntity

源码：`sms/jia-sms-core/src/main/java/cn/jia/sms/entity/SmsConfigEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `shortName` | `shortName` | `String` |
| `replyUrl` | `replyUrl` | `String` |
| `remain` | `remain` | `Integer` |

### Model SmsReplyEntity

源码：`sms/jia-sms-core/src/main/java/cn/jia/sms/entity/SmsReplyEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `Long` |
| `msgid` | `msgid` | `String` |
| `mobile` | `mobile` | `String` |
| `xh` | `xh` | `String` |
| `content` | `content` | `String` |

### Model SmsSendVO

源码：`sms/jia-sms-api/src/main/java/cn/jia/sms/entity/SmsSendVO.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `mobile` | `mobile` | `String` |
| `content` | `content` | `String` |
| `xh` | `xh` | `String` |
| `msgid` | `msgid` | `String` |
| `timeStart` | `timeStart` | `Long` |
| `timeEnd` | `timeEnd` | `Long` |

### Model SmsTemplateEntity

源码：`sms/jia-sms-core/src/main/java/cn/jia/sms/entity/SmsTemplateEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `templateId` | `templateId` | `String` |
| `name` | `name` | `String` |
| `title` | `title` | `String` |
| `content` | `content` | `String` |
| `msgType` | `msgType` | `Integer` |
| `type` | `type` | `Integer` |
| `status` | `status` | `Integer` |

### Model SmsTemplateVO

源码：`sms/jia-sms-api/src/main/java/cn/jia/sms/entity/SmsTemplateVO.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `templateId` | `templateId` | `String` |
| `name` | `name` | `String` |
| `title` | `title` | `String` |
| `content` | `content` | `String` |
| `msgType` | `msgType` | `Integer` |
| `type` | `type` | `Integer` |
| `status` | `status` | `Integer` |
| `clientStrictFlag` | `clientStrictFlag` | `Integer` |
| `createTimeStart` | `createTimeStart` | `Long` |
| `createTimeEnd` | `createTimeEnd` | `Long` |
| `updateTimeStart` | `updateTimeStart` | `Long` |
| `updateTimeEnd` | `updateTimeEnd` | `Long` |
| `nameLike` | `nameLike` | `String` |
