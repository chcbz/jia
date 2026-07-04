# OAuth 模块接口文档

来源模块：`oauth`

本文面向前端对接，每个接口包含请求方式、地址、参数位置、必填性、请求体字段和响应结构。

## 通用响应结构

- 单对象或操作结果：`JsonResult<T>`，字段：`code`、`msg`、`status`、`location`、`data`。
- 分页列表：`JsonResultPage<T>`，字段：`code`、`msg`、`status`、`pageNum`、`total`、`data`（数组）。
- 成功码默认为 `E0`，HTTP 状态通常为 `200`。

## 接口汇总

| 请求方式 | 地址 | Controller | 响应 |
| --- | --- | --- | --- |
| GET | `/token` | `AuthenticationController.token` | OAuth2AuthorizedClient |
| GET | `/resource` | `AuthenticationController.token` | Object (null) |
| GET | `/oauth/third-party/wxmp` | `OauthController.thirdPartyWxMp` | String |
| GET | `/oauth/third-party/weixin` | `OauthController.thirdPartyWeiXin` | String |
| GET | `/oauth/third-party/weibo` | `OauthController.thirdPartyWeiBo` | String |
| GET | `/oauth/third-party/github` | `OauthController.thirdPartyGithub` | String |
| GET | `/oauth/third-party/autologin` | `OauthController.thirdPartyAutoLogin` | String |
| ANY | `/oauth/confirm_access` | `OauthController.getAccessConfirmation` | ModelAndView |
| GET | `/oauth/clientid` | `OauthController.findClientId` | JsonResult<client.getClientId()> |
| GET | `/oauth/client/get` | `OauthController.find` | JsonResult<[OauthClientEntity](#model-oauthcliententity)> |
| POST | `/oauth/client/update` | `OauthController.updateClient` | JsonResult<client> |
| GET | `/oauth/user` | `OauthController.info` | JsonResult<user> |

## 接口明细

### AuthenticationController `/`

源码：`oauth/jia-oauth-client-starter/src/main/java/cn/jia/oauth/api/AuthenticationController.java`

#### GET `/token`

- 处理方法：`AuthenticationController.token`
- 响应：OAuth2AuthorizedClient

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `oAuth2AuthorizedClient` | security context | - | OAuth2AuthorizedClient | 查询/表单参数 |

响应 data 说明：
- 按 `OAuth2AuthorizedClient` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

### AuthenticationController `/`

源码：`oauth/jia-oauth-resource/src/main/java/cn/jia/oauth/api/AuthenticationController.java`

#### GET `/resource`

- 处理方法：`AuthenticationController.token`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| - | - | - | - | 无入参 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

### OauthController `/oauth`

源码：`oauth/jia-oauth-service/src/main/java/cn/jia/oauth/api/OauthController.java`

#### GET `/oauth/third-party/wxmp`

- 处理方法：`OauthController.thirdPartyWxMp`
- 响应：String

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `code` | query | yes | String | 查询/表单参数 |
| `state` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `String` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/oauth/third-party/weixin`

- 处理方法：`OauthController.thirdPartyWeiXin`
- 响应：String

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `code` | query | yes | String | 查询/表单参数 |
| `state` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `String` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/oauth/third-party/weibo`

- 处理方法：`OauthController.thirdPartyWeiBo`
- 响应：String

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `code` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `String` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/oauth/third-party/github`

- 处理方法：`OauthController.thirdPartyGithub`
- 响应：String

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `code` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `String` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/oauth/third-party/autologin`

- 处理方法：`OauthController.thirdPartyAutoLogin`
- 响应：String

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| - | - | - | - | 无入参 |

响应 data 说明：
- 按 `String` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### ANY `/oauth/confirm_access`

- 处理方法：`OauthController.getAccessConfirmation`
- 响应：ModelAndView

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `model` | query/form | yes | Map<String, Object> | 查询/表单参数 |

响应 data 说明：
- 按 `ModelAndView` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/oauth/clientid`

- 处理方法：`OauthController.findClientId`
- 响应：[JsonResult](#model-jsonresult)<expression: client.getClientId()>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `appcn` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<expression: client.getClientId()>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/oauth/client/get`

- 处理方法：`OauthController.find`
- 响应：[JsonResult](#model-jsonresult)<[OauthClientEntity](#model-oauthcliententity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| - | - | - | - | 无入参 |

响应 data 说明：
- 按 `JsonResult<OauthClientEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/oauth/client/update`

- 处理方法：`OauthController.updateClient`
- 响应：[JsonResult](#model-jsonresult)<[OauthClientEntity](#model-oauthcliententity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `client` | body | yes | [OauthClientEntity](#model-oauthcliententity) | 请求体 |

请求体字段：

[OauthClientEntity](#model-oauthcliententity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `OauthClientEntity` |
| `updateTime` | `Long` | 源自 `OauthClientEntity` |
| `tenantId` | `String` | 源自 `OauthClientEntity` |
| `clientId` | `String` | 源自 `OauthClientEntity` |
| `id` | `String` | 源自 `OauthClientEntity` |
| `clientIdIssuedAt` | `Date` | 源自 `OauthClientEntity` |
| `clientSecret` | `String` | 源自 `OauthClientEntity` |
| `clientSecretExpiresAt` | `Date` | 源自 `OauthClientEntity` |
| `clientName` | `String` | 源自 `OauthClientEntity` |
| `clientAuthenticationMethods` | `String` | 源自 `OauthClientEntity` |
| `appcn` | `String` | 源自 `OauthClientEntity` |
| `authorizationGrantTypes` | `String` | 源自 `OauthClientEntity` |
| `redirectUris` | `String` | 源自 `OauthClientEntity` |
| `postLogoutRedirectUris` | `String` | 源自 `OauthClientEntity` |
| `scopes` | `String` | 源自 `OauthClientEntity` |
| `clientSettings` | `String` | 源自 `OauthClientEntity` |
| `tokenSettings` | `String` | 源自 `OauthClientEntity` |

响应 data 说明：
- 按 `JsonResult<OauthClientEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/oauth/user`

- 处理方法：`OauthController.info`
- 响应：[JsonResult](#model-jsonresult)<Principal>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `user` | query/form | yes | Principal | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<Principal>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

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

### Model OauthClientEntity

源码：`oauth/jia-oauth-core/src/main/java/cn/jia/oauth/entity/OauthClientEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `id` | `id` | `String` |
| `clientIdIssuedAt` | `clientIdIssuedAt` | `Date` |
| `clientSecret` | `clientSecret` | `String` |
| `clientSecretExpiresAt` | `clientSecretExpiresAt` | `Date` |
| `clientName` | `clientName` | `String` |
| `clientAuthenticationMethods` | `clientAuthenticationMethods` | `String` |
| `appcn` | `appcn` | `String` |
| `authorizationGrantTypes` | `authorizationGrantTypes` | `String` |
| `redirectUris` | `redirectUris` | `String` |
| `postLogoutRedirectUris` | `postLogoutRedirectUris` | `String` |
| `scopes` | `scopes` | `String` |
| `clientSettings` | `clientSettings` | `String` |
| `tokenSettings` | `tokenSettings` | `String` |
