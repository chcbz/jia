# WX 微信模块接口文档

来源模块：`wx`

本文面向前端对接，每个接口包含请求方式、地址、参数位置、必填性、请求体字段和响应结构。

## 通用响应结构

- 单对象或操作结果：`JsonResult<T>`，字段：`code`、`msg`、`status`、`location`、`data`。
- 分页列表：`JsonResultPage<T>`，字段：`code`、`msg`、`status`、`pageNum`、`total`、`data`（数组）。
- 成功码默认为 `E0`，HTTP 状态通常为 `200`。

## 接口汇总

| 请求方式 | 地址 | Controller | 响应 |
| --- | --- | --- | --- |
| GET | `/wx/mp/checksignature` | `WxMpController.checkSignature` | Object (echostr) |
| POST | `/wx/mp/checksignature` | `WxMpController.receiveMsg` | WxMpXmlOutTransferKefuMessage |
| ANY | `/wx/mp/menu/create` | `WxMpController.menuCreate` | JsonResult<Void> |
| ANY | `/wx/mp/menu/get` | `WxMpController.menuGet` | JsonResult<mpInfoService.findWxMpService(request).getMenuServ> |
| ANY | `/wx/mp/menu/delete` | `WxMpController.menuDelete` | JsonResult<Void> |
| ANY | `/wx/mp/user/get` | `WxMpController.userGet` | JsonResult<mpInfoService.findWxMpService(request).getUserServ> |
| ANY | `/wx/mp/user/info/batchget` | `WxMpController.userInfoBatchGet` | JsonResult<mpInfoService.findWxMpService(request).getUserServ> |
| ANY | `/wx/mp/user/sync` | `WxMpController.userSync` | JsonResult<Void> |
| ANY | `/wx/mp/oauth2/authorize_url` | `WxMpController.oauth2Authorize` | JsonResult<mpInfoService.findWxMpService(request).getOAuth2Se> |
| ANY | `/wx/mp/oauth2/access_token` | `WxMpController.oauth2AccessToken` | JsonResult<mpInfoService.findWxMpService(request).getOAuth2Se> |
| ANY | `/wx/mp/oauth2/userinfo` | `WxMpController.oauth2Userinfo` | JsonResult<mpInfoService.findWxMpService(request).getOAuth2Se> |
| ANY | `/wx/mp/oauth2/refresh_token` | `WxMpController.oauth2RefreshToken` | JsonResult<mpInfoService.findWxMpService(request).getOAuth2Se> |
| ANY | `/wx/mp/oauth2/auth` | `WxMpController.oauth2ValidateAccessToken` | JsonResult<mpInfoService.findWxMpService(request).getOAuth2Se> |
| ANY | `/wx/mp/jsapi/signature` | `WxMpController.createJsapiSignature` | JsonResult<mpInfoService.findWxMpService(request).createJsapi> |
| ANY | `/wx/mp/customservice/getkflist` | `WxMpController.getkflist` | JsonResult<mpInfoService.findWxMpService(request).getKefuServ> |
| ANY | `/wx/mp/customservice/kfsession/getwaitcase` | `WxMpController.getwaitcase` | JsonResult<mpInfoService.findWxMpService(request).getKefuServ> |
| ANY | `/wx/mp/message/custom/send` | `WxMpController.customMessageSend` | JsonResult<mpInfoService.findWxMpService(request).getKefuServ> |
| ANY | `/wx/mp/message/template/send` | `WxMpController.templateMessageSend` | JsonResult<mpInfoService.findWxMpService(request).getTemplate> |
| ANY | `/wx/mp/material/batchget` | `WxMpController.materialBatchGet` | JsonResult<mpInfoService.findWxMpService(request).getMaterial> |
| ANY | `/wx/mp/material/add_news` | `WxMpController.materialAddNews` | JsonResult<WxMpMaterialUploadResult> |
| ANY | `/wx/mp/material/add_material` | `WxMpController.materialAdd` | JsonResult<WxMpMaterialUploadResult> |
| GET | `/wx/mp/info/get` | `WxMpController.findMpInfoById` | JsonResult<[MpInfoEntity](#model-mpinfoentity)> |
| POST | `/wx/mp/info/create` | `WxMpController.createMpInfo` | JsonResult<info> |
| POST | `/wx/mp/info/update` | `WxMpController.updateMpInfo` | JsonResult<info> |
| GET | `/wx/mp/info/delete` | `WxMpController.deleteMpInfo` | JsonResult<Void> |
| POST | `/wx/mp/info/list` | `WxMpController.listMpInfo` | JsonResultPage<[MpInfoEntity](#model-mpinfoentity)> |
| GET | `/wx/pay/queryOrder` | `WxPayController.queryOrder` | WxPayOrderQueryResult |
| GET | `/wx/pay/closeOrder/{outTradeNo}` | `WxPayController.closeOrder` | WxPayOrderCloseResult |
| POST | `/wx/pay/createOrder` | `WxPayController.createOrder` | <T> T |
| GET | `/wx/pay/createOrder` | `WxPayController.createOrder` | <T> T |
| POST | `/wx/pay/unifiedOrder` | `WxPayController.unifiedOrder` | WxPayUnifiedOrderResult |
| GET | `/wx/pay/unifiedOrder` | `WxPayController.unifiedOrder` | WxPayUnifiedOrderResult |
| POST | `/wx/pay/refund` | `WxPayController.refund` | WxPayRefundResult |
| GET | `/wx/pay/refundQuery` | `WxPayController.refundQuery` | WxPayRefundQueryResult |
| POST | `/wx/pay/parseOrderNotifyResult` | `WxPayController.parseOrderNotifyResult` | Object |
| POST | `/wx/pay/parseRefundNotifyResult` | `WxPayController.parseRefundNotifyResult` | WxPayRefundNotifyResult |
| POST | `/wx/pay/parseScanPayNotifyResult` | `WxPayController.parseScanPayNotifyResult` | Object |
| POST | `/wx/pay/sendRedpack` | `WxPayController.sendRedpack` | WxPaySendRedpackResult |
| GET | `/wx/pay/queryRedpack/{mchBillNo}` | `WxPayController.queryRedpack` | WxPayRedpackQueryResult |
| POST | `/wx/pay/entPay` | `WxPayController.entPay` | EntPayResult |
| GET | `/wx/pay/queryEntPay/{partnerTradeNo}` | `WxPayController.queryEntPay` | EntPayQueryResult |
| POST | `/wx/pay/scanPay/qrcode` | `WxPayController.createScanPayQrcodeMode1` | [ResponseEntity](#model-responseentity)<byte[]> |
| GET | `/wx/pay/scanPay/qrcodeLink` | `WxPayController.createScanPayQrcodeMode1` | String |
| POST | `/wx/pay/report` | `WxPayController.report` | void |
| GET | `/wx/pay/downloadBill` | `WxPayController.downloadBill` | WxPayBillResult |
| POST | `/wx/pay/micropay` | `WxPayController.micropay` | WxPayMicropayResult |
| POST | `/wx/pay/reverseOrder` | `WxPayController.reverseOrder` | WxPayOrderReverseResult |
| GET | `/wx/pay/getSandboxSignKey` | `WxPayController.getSandboxSignKey` | String |
| POST | `/wx/pay/sendCoupon` | `WxPayController.sendCoupon` | WxPayCouponSendResult |
| POST | `/wx/pay/queryCouponStock` | `WxPayController.queryCouponStock` | WxPayCouponStockQueryResult |
| POST | `/wx/pay/queryCouponInfo` | `WxPayController.queryCouponInfo` | WxPayCouponInfoQueryResult |
| POST | `/wx/pay/queryComment` | `WxPayController.queryComment` | String |
| GET | `/wx/pay/info/get` | `WxPayController.findPayInfoById` | JsonResult<[PayInfoEntity](#model-payinfoentity)> |
| POST | `/wx/pay/info/create` | `WxPayController.createPayInfo` | JsonResult<info> |
| POST | `/wx/pay/info/update` | `WxPayController.updatePayInfo` | JsonResult<info> |
| GET | `/wx/pay/info/delete` | `WxPayController.deletePayInfo` | JsonResult<Void> |
| POST | `/wx/pay/info/list` | `WxPayController.listPayInfo` | JsonResultPage<[PayInfoEntity](#model-payinfoentity)> |

## 接口明细

### WxMpController `/wx/mp`

源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java`

#### GET `/wx/mp/checksignature`

- 处理方法：`WxMpController.checkSignature`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `signature` | query | yes | String | 查询/表单参数 |
| `nonce` | query | yes | String | 查询/表单参数 |
| `timestamp` | query | yes | String | 查询/表单参数 |
| `echostr` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/wx/mp/checksignature`

- 处理方法：`WxMpController.receiveMsg`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `msg` | body | yes | String | 请求体 |

请求体字段：

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### ANY `/wx/mp/menu/create`

- 处理方法：`WxMpController.menuCreate`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `menu` | body | yes | String | 请求体 |

请求体字段：

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### ANY `/wx/mp/menu/get`

- 处理方法：`WxMpController.menuGet`
- 响应：[JsonResult](#model-jsonresult)<expression: mpInfoService.findWxMpService(request).getMenuService().menu>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| - | - | - | - | 无入参 |

响应 data 说明：
- 按 `JsonResult<expression: mpInfoService.findWxMpService(request).getMenuService().menu>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### ANY `/wx/mp/menu/delete`

- 处理方法：`WxMpController.menuDelete`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| - | - | - | - | 无入参 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### ANY `/wx/mp/user/get`

- 处理方法：`WxMpController.userGet`
- 响应：[JsonResult](#model-jsonresult)<expression: mpInfoService.findWxMpService(request).getUserService().user>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `next_openid` | query | no | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<expression: mpInfoService.findWxMpService(request).getUserService().user>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### ANY `/wx/mp/user/info/batchget`

- 处理方法：`WxMpController.userInfoBatchGet`
- 响应：[JsonResult](#model-jsonresult)<expression: mpInfoService.findWxMpService(request).getUserService().user>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `openids` | body | yes | List<String> | 请求体 |

请求体字段：

响应 data 说明：
- 按 `JsonResult<expression: mpInfoService.findWxMpService(request).getUserService().user>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### ANY `/wx/mp/user/sync`

- 处理方法：`WxMpController.userSync`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| - | - | - | - | 无入参 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### ANY `/wx/mp/oauth2/authorize_url`

- 处理方法：`WxMpController.oauth2Authorize`
- 响应：[JsonResult](#model-jsonresult)<expression: mpInfoService.findWxMpService(request).getOAuth2Service().bu>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `redirect_uri` | query | yes | String | 查询/表单参数 |
| `scope` | query | yes | String | 查询/表单参数 |
| `state` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<expression: mpInfoService.findWxMpService(request).getOAuth2Service().bu>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### ANY `/wx/mp/oauth2/access_token`

- 处理方法：`WxMpController.oauth2AccessToken`
- 响应：[JsonResult](#model-jsonresult)<expression: mpInfoService.findWxMpService(request).getOAuth2Service().ge>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `code` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<expression: mpInfoService.findWxMpService(request).getOAuth2Service().ge>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### ANY `/wx/mp/oauth2/userinfo`

- 处理方法：`WxMpController.oauth2Userinfo`
- 响应：[JsonResult](#model-jsonresult)<expression: mpInfoService.findWxMpService(request).getOAuth2Service().ge>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `access_token` | query | yes | String | 查询/表单参数 |
| `openid` | query | yes | String | 查询/表单参数 |
| `lang` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<expression: mpInfoService.findWxMpService(request).getOAuth2Service().ge>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### ANY `/wx/mp/oauth2/refresh_token`

- 处理方法：`WxMpController.oauth2RefreshToken`
- 响应：[JsonResult](#model-jsonresult)<expression: mpInfoService.findWxMpService(request).getOAuth2Service().re>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `refresh_token` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<expression: mpInfoService.findWxMpService(request).getOAuth2Service().re>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### ANY `/wx/mp/oauth2/auth`

- 处理方法：`WxMpController.oauth2ValidateAccessToken`
- 响应：[JsonResult](#model-jsonresult)<expression: mpInfoService.findWxMpService(request).getOAuth2Service().va>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `access_token` | query | yes | String | 查询/表单参数 |
| `openid` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<expression: mpInfoService.findWxMpService(request).getOAuth2Service().va>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### ANY `/wx/mp/jsapi/signature`

- 处理方法：`WxMpController.createJsapiSignature`
- 响应：[JsonResult](#model-jsonresult)<expression: mpInfoService.findWxMpService(request).createJsapiSignature(>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `url` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<expression: mpInfoService.findWxMpService(request).createJsapiSignature(>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### ANY `/wx/mp/customservice/getkflist`

- 处理方法：`WxMpController.getkflist`
- 响应：[JsonResult](#model-jsonresult)<expression: mpInfoService.findWxMpService(request).getKefuService().kfLi>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| - | - | - | - | 无入参 |

响应 data 说明：
- 按 `JsonResult<expression: mpInfoService.findWxMpService(request).getKefuService().kfLi>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### ANY `/wx/mp/customservice/kfsession/getwaitcase`

- 处理方法：`WxMpController.getwaitcase`
- 响应：[JsonResult](#model-jsonresult)<expression: mpInfoService.findWxMpService(request).getKefuService().kfSe>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| - | - | - | - | 无入参 |

响应 data 说明：
- 按 `JsonResult<expression: mpInfoService.findWxMpService(request).getKefuService().kfSe>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### ANY `/wx/mp/message/custom/send`

- 处理方法：`WxMpController.customMessageSend`
- 响应：[JsonResult](#model-jsonresult)<expression: mpInfoService.findWxMpService(request).getKefuService().send>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `message` | body | yes | WxMpKefuMessage | 请求体 |

请求体字段：

响应 data 说明：
- 按 `JsonResult<expression: mpInfoService.findWxMpService(request).getKefuService().send>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### ANY `/wx/mp/message/template/send`

- 处理方法：`WxMpController.templateMessageSend`
- 响应：[JsonResult](#model-jsonresult)<expression: mpInfoService.findWxMpService(request).getTemplateMsgService>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `templateMessage` | body | yes | WxMpTemplateMessage | 请求体 |

请求体字段：

响应 data 说明：
- 按 `JsonResult<expression: mpInfoService.findWxMpService(request).getTemplateMsgService>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### ANY `/wx/mp/material/batchget`

- 处理方法：`WxMpController.materialBatchGet`
- 响应：[JsonResult](#model-jsonresult)<expression: mpInfoService.findWxMpService(request).getMaterialService().>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `type` | query | yes | String | 查询/表单参数 |
| `offset` | query | yes | Integer | 查询/表单参数 |
| `count` | query | yes | Integer | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<expression: mpInfoService.findWxMpService(request).getMaterialService().>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### ANY `/wx/mp/material/add_news`

- 处理方法：`WxMpController.materialAddNews`
- 响应：[JsonResult](#model-jsonresult)<`WxMpMaterialUploadResult`>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `news` | body | yes | WxMpMaterialNews | 请求体 |

请求体字段：

响应 data 说明：
- 按 `JsonResult<WxMpMaterialUploadResult>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### ANY `/wx/mp/material/add_material`

- 处理方法：`WxMpController.materialAdd`
- 响应：[JsonResult](#model-jsonresult)<`WxMpMaterialUploadResult`>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `file` | multipart | no | MultipartFile | 文件/表单参数 |
| `type` | query | yes | String | 查询/表单参数 |

请求体字段：

响应 data 说明：
- 按 `JsonResult<WxMpMaterialUploadResult>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/wx/mp/info/get`

- 处理方法：`WxMpController.findMpInfoById`
- 响应：[JsonResult](#model-jsonresult)<[MpInfoEntity](#model-mpinfoentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<MpInfoEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/wx/mp/info/create`

- 处理方法：`WxMpController.createMpInfo`
- 响应：[JsonResult](#model-jsonresult)<[MpInfoEntity](#model-mpinfoentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `info` | body | yes | [MpInfoEntity](#model-mpinfoentity) | 请求体 |

请求体字段：

[MpInfoEntity](#model-mpinfoentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `MpInfoEntity` |
| `updateTime` | `Long` | 源自 `MpInfoEntity` |
| `tenantId` | `String` | 源自 `MpInfoEntity` |
| `clientId` | `String` | 源自 `MpInfoEntity` |
| `acid` | `Long` | 源自 `MpInfoEntity` |
| `token` | `String` | 源自 `MpInfoEntity` |
| `accessToken` | `String` | 源自 `MpInfoEntity` |
| `encodingaeskey` | `String` | 源自 `MpInfoEntity` |
| `level` | `Integer` | 源自 `MpInfoEntity` |
| `name` | `String` | 源自 `MpInfoEntity` |
| `account` | `String` | 源自 `MpInfoEntity` |
| `original` | `String` | 源自 `MpInfoEntity` |
| `signature` | `String` | 源自 `MpInfoEntity` |
| `country` | `String` | 源自 `MpInfoEntity` |
| `province` | `String` | 源自 `MpInfoEntity` |
| `city` | `String` | 源自 `MpInfoEntity` |
| `username` | `String` | 源自 `MpInfoEntity` |
| `password` | `String` | 源自 `MpInfoEntity` |
| `status` | `Integer` | 源自 `MpInfoEntity` |
| `appid` | `String` | 源自 `MpInfoEntity` |
| `secret` | `String` | 源自 `MpInfoEntity` |
| `styleid` | `Integer` | 源自 `MpInfoEntity` |
| `subscribeurl` | `String` | 源自 `MpInfoEntity` |
| `authRefreshToken` | `String` | 源自 `MpInfoEntity` |

响应 data 说明：
- 按 `JsonResult<MpInfoEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/wx/mp/info/update`

- 处理方法：`WxMpController.updateMpInfo`
- 响应：[JsonResult](#model-jsonresult)<[MpInfoEntity](#model-mpinfoentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `info` | body | yes | [MpInfoEntity](#model-mpinfoentity) | 请求体 |

请求体字段：

[MpInfoEntity](#model-mpinfoentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `MpInfoEntity` |
| `updateTime` | `Long` | 源自 `MpInfoEntity` |
| `tenantId` | `String` | 源自 `MpInfoEntity` |
| `clientId` | `String` | 源自 `MpInfoEntity` |
| `acid` | `Long` | 源自 `MpInfoEntity` |
| `token` | `String` | 源自 `MpInfoEntity` |
| `accessToken` | `String` | 源自 `MpInfoEntity` |
| `encodingaeskey` | `String` | 源自 `MpInfoEntity` |
| `level` | `Integer` | 源自 `MpInfoEntity` |
| `name` | `String` | 源自 `MpInfoEntity` |
| `account` | `String` | 源自 `MpInfoEntity` |
| `original` | `String` | 源自 `MpInfoEntity` |
| `signature` | `String` | 源自 `MpInfoEntity` |
| `country` | `String` | 源自 `MpInfoEntity` |
| `province` | `String` | 源自 `MpInfoEntity` |
| `city` | `String` | 源自 `MpInfoEntity` |
| `username` | `String` | 源自 `MpInfoEntity` |
| `password` | `String` | 源自 `MpInfoEntity` |
| `status` | `Integer` | 源自 `MpInfoEntity` |
| `appid` | `String` | 源自 `MpInfoEntity` |
| `secret` | `String` | 源自 `MpInfoEntity` |
| `styleid` | `Integer` | 源自 `MpInfoEntity` |
| `subscribeurl` | `String` | 源自 `MpInfoEntity` |
| `authRefreshToken` | `String` | 源自 `MpInfoEntity` |

响应 data 说明：
- 按 `JsonResult<MpInfoEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/wx/mp/info/delete`

- 处理方法：`WxMpController.deleteMpInfo`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/wx/mp/info/list`

- 处理方法：`WxMpController.listMpInfo`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[MpInfoVO](#model-mpinfovo)> | 请求体 |

请求体字段：

[MpInfoVO](#model-mpinfovo)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `MpInfoVO` |
| `updateTime` | `Long` | 源自 `MpInfoVO` |
| `tenantId` | `String` | 源自 `MpInfoVO` |
| `clientId` | `String` | 源自 `MpInfoVO` |
| `acid` | `Long` | 源自 `MpInfoVO` |
| `token` | `String` | 源自 `MpInfoVO` |
| `accessToken` | `String` | 源自 `MpInfoVO` |
| `encodingaeskey` | `String` | 源自 `MpInfoVO` |
| `level` | `Integer` | 源自 `MpInfoVO` |
| `name` | `String` | 源自 `MpInfoVO` |
| `account` | `String` | 源自 `MpInfoVO` |
| `original` | `String` | 源自 `MpInfoVO` |
| `signature` | `String` | 源自 `MpInfoVO` |
| `country` | `String` | 源自 `MpInfoVO` |
| `province` | `String` | 源自 `MpInfoVO` |
| `city` | `String` | 源自 `MpInfoVO` |
| `username` | `String` | 源自 `MpInfoVO` |
| `password` | `String` | 源自 `MpInfoVO` |
| `status` | `Integer` | 源自 `MpInfoVO` |
| `appid` | `String` | 源自 `MpInfoVO` |
| `secret` | `String` | 源自 `MpInfoVO` |
| `styleid` | `Integer` | 源自 `MpInfoVO` |
| `subscribeurl` | `String` | 源自 `MpInfoVO` |
| `authRefreshToken` | `String` | 源自 `MpInfoVO` |
| `clientIdList` | `List<String>` | 源自 `MpInfoVO` |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

### WxPayController `/wx/pay`

源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java`

#### GET `/wx/pay/queryOrder`

- 处理方法：`WxPayController.queryOrder`
- 响应：`WxPayOrderQueryResult`

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `transactionId` | query | no | String | 查询/表单参数 |
| `outTradeNo` | query | no | String | 查询/表单参数 |

响应 data 说明：
- 按 `WxPayOrderQueryResult` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/wx/pay/closeOrder/{outTradeNo}`

- 处理方法：`WxPayController.closeOrder`
- 响应：`WxPayOrderCloseResult`

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `outTradeNo` | path | yes | String | 路径参数 |

响应 data 说明：
- 按 `WxPayOrderCloseResult` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/wx/pay/createOrder`

- 处理方法：`WxPayController.createOrder`
- 响应：<T> T

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `request` | body | yes | `WxPayUnifiedOrderRequest` | 请求体 |

请求体字段：

响应 data 说明：
- 按 `<T> T` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/wx/pay/createOrder`

- 处理方法：`WxPayController.createOrder`
- 响应：<T> T

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `productId` | query | no | String | 查询/表单参数 |
| `jiacn` | query | no | String | 查询/表单参数 |
| `outTradeNo` | query | no | String | 查询/表单参数 |
| `tradeType` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `<T> T` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/wx/pay/unifiedOrder`

- 处理方法：`WxPayController.unifiedOrder`
- 响应：`WxPayUnifiedOrderResult`

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `request` | body | yes | `WxPayUnifiedOrderRequest` | 请求体 |

请求体字段：

响应 data 说明：
- 按 `WxPayUnifiedOrderResult` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/wx/pay/unifiedOrder`

- 处理方法：`WxPayController.unifiedOrder`
- 响应：`WxPayUnifiedOrderResult`

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `productId` | query | yes | String | 查询/表单参数 |
| `openid` | query | yes | String | 查询/表单参数 |
| `tradeType` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `WxPayUnifiedOrderResult` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/wx/pay/refund`

- 处理方法：`WxPayController.refund`
- 响应：`WxPayRefundResult`

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `request` | body | yes | `WxPayRefundRequest` | 请求体 |

请求体字段：

响应 data 说明：
- 按 `WxPayRefundResult` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/wx/pay/refundQuery`

- 处理方法：`WxPayController.refundQuery`
- 响应：`WxPayRefundQueryResult`

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `transactionId` | query | no | String | 查询/表单参数 |
| `outTradeNo` | query | no | String | 查询/表单参数 |
| `outRefundNo` | query | no | String | 查询/表单参数 |
| `refundId` | query | no | String | 查询/表单参数 |

响应 data 说明：
- 按 `WxPayRefundQueryResult` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/wx/pay/parseOrderNotifyResult`

- 处理方法：`WxPayController.parseOrderNotifyResult`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `xmlData` | body | yes | String | 请求体 |

请求体字段：

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/wx/pay/parseRefundNotifyResult`

- 处理方法：`WxPayController.parseRefundNotifyResult`
- 响应：`WxPayRefundNotifyResult`

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `xmlData` | body | yes | String | 请求体 |

请求体字段：

响应 data 说明：
- 按 `WxPayRefundNotifyResult` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/wx/pay/parseScanPayNotifyResult`

- 处理方法：`WxPayController.parseScanPayNotifyResult`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `xmlData` | body | yes | String | 请求体 |

请求体字段：

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/wx/pay/sendRedpack`

- 处理方法：`WxPayController.sendRedpack`
- 响应：`WxPaySendRedpackResult`

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `request` | body | yes | `WxPaySendRedpackRequest` | 请求体 |

请求体字段：

响应 data 说明：
- 按 `WxPaySendRedpackResult` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/wx/pay/queryRedpack/{mchBillNo}`

- 处理方法：`WxPayController.queryRedpack`
- 响应：`WxPayRedpackQueryResult`

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `mchBillNo` | path | yes | String | 路径参数 |

响应 data 说明：
- 按 `WxPayRedpackQueryResult` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/wx/pay/entPay`

- 处理方法：`WxPayController.entPay`
- 响应：`EntPayResult`

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `request` | body | yes | `EntPayRequest` | 请求体 |

请求体字段：

响应 data 说明：
- 按 `EntPayResult` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/wx/pay/queryEntPay/{partnerTradeNo}`

- 处理方法：`WxPayController.queryEntPay`
- 响应：`EntPayQueryResult`

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `partnerTradeNo` | path | yes | String | 路径参数 |

响应 data 说明：
- 按 `EntPayQueryResult` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/wx/pay/scanPay/qrcode`

- 处理方法：`WxPayController.createScanPayQrcodeMode1`
- 响应：`ResponseEntity`<byte[]>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `productId` | query | yes | String | 查询/表单参数 |
| `logoFile` | multipart | no | MultipartFile | 文件/表单参数 |
| `sideLength` | query | no | Integer | 查询/表单参数 |

请求体字段：

响应 data 说明：
- 按 `ResponseEntity<byte[]>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/wx/pay/scanPay/qrcodeLink`

- 处理方法：`WxPayController.createScanPayQrcodeMode1`
- 响应：String

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `productId` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `String` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/wx/pay/report`

- 处理方法：`WxPayController.report`
- 响应：void

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `request` | body | yes | `WxPayReportRequest` | 请求体 |

请求体字段：

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### GET `/wx/pay/downloadBill`

- 处理方法：`WxPayController.downloadBill`
- 响应：`WxPayBillResult`

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `billDate` | query | yes | String | 查询/表单参数 |
| `billType` | query | yes | String | 查询/表单参数 |
| `tarType` | query | yes | String | 查询/表单参数 |
| `deviceInfo` | query | yes | String | 查询/表单参数 |

响应 data 说明：
- 按 `WxPayBillResult` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/wx/pay/micropay`

- 处理方法：`WxPayController.micropay`
- 响应：`WxPayMicropayResult`

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `request` | body | yes | `WxPayMicropayRequest` | 请求体 |

请求体字段：

响应 data 说明：
- 按 `WxPayMicropayResult` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/wx/pay/reverseOrder`

- 处理方法：`WxPayController.reverseOrder`
- 响应：`WxPayOrderReverseResult`

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `request` | body | yes | `WxPayOrderReverseRequest` | 请求体 |

请求体字段：

响应 data 说明：
- 按 `WxPayOrderReverseResult` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/wx/pay/getSandboxSignKey`

- 处理方法：`WxPayController.getSandboxSignKey`
- 响应：String

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| - | - | - | - | 无入参 |

响应 data 说明：
- 按 `String` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/wx/pay/sendCoupon`

- 处理方法：`WxPayController.sendCoupon`
- 响应：`WxPayCouponSendResult`

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `request` | body | yes | `WxPayCouponSendRequest` | 请求体 |

请求体字段：

响应 data 说明：
- 按 `WxPayCouponSendResult` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/wx/pay/queryCouponStock`

- 处理方法：`WxPayController.queryCouponStock`
- 响应：`WxPayCouponStockQueryResult`

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `request` | body | yes | `WxPayCouponStockQueryRequest` | 请求体 |

请求体字段：

响应 data 说明：
- 按 `WxPayCouponStockQueryResult` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/wx/pay/queryCouponInfo`

- 处理方法：`WxPayController.queryCouponInfo`
- 响应：`WxPayCouponInfoQueryResult`

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `request` | body | yes | `WxPayCouponInfoQueryRequest` | 请求体 |

请求体字段：

响应 data 说明：
- 按 `WxPayCouponInfoQueryResult` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/wx/pay/queryComment`

- 处理方法：`WxPayController.queryComment`
- 响应：String

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `beginDate` | query/form | yes | Date | 查询/表单参数 |
| `endDate` | query/form | yes | Date | 查询/表单参数 |
| `offset` | query/form | yes | Integer | 查询/表单参数 |
| `limit` | query/form | yes | Integer | 查询/表单参数 |

响应 data 说明：
- 按 `String` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/wx/pay/info/get`

- 处理方法：`WxPayController.findPayInfoById`
- 响应：[JsonResult](#model-jsonresult)<[PayInfoEntity](#model-payinfoentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 按 `JsonResult<PayInfoEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/wx/pay/info/create`

- 处理方法：`WxPayController.createPayInfo`
- 响应：[JsonResult](#model-jsonresult)<[PayInfoEntity](#model-payinfoentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `info` | body | yes | [PayInfoEntity](#model-payinfoentity) | 请求体 |

请求体字段：

[PayInfoEntity](#model-payinfoentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `PayInfoEntity` |
| `updateTime` | `Long` | 源自 `PayInfoEntity` |
| `tenantId` | `String` | 源自 `PayInfoEntity` |
| `clientId` | `String` | 源自 `PayInfoEntity` |
| `acid` | `Long` | 源自 `PayInfoEntity` |
| `name` | `String` | 源自 `PayInfoEntity` |
| `account` | `String` | 源自 `PayInfoEntity` |
| `country` | `String` | 源自 `PayInfoEntity` |
| `province` | `String` | 源自 `PayInfoEntity` |
| `city` | `String` | 源自 `PayInfoEntity` |
| `username` | `String` | 源自 `PayInfoEntity` |
| `password` | `String` | 源自 `PayInfoEntity` |
| `status` | `Integer` | 源自 `PayInfoEntity` |
| `appId` | `String` | 源自 `PayInfoEntity` |
| `subAppId` | `String` | 源自 `PayInfoEntity` |
| `mchId` | `String` | 源自 `PayInfoEntity` |
| `mchKey` | `String` | 源自 `PayInfoEntity` |
| `subMchId` | `String` | 源自 `PayInfoEntity` |
| `notifyUrl` | `String` | 源自 `PayInfoEntity` |
| `tradeType` | `String` | 源自 `PayInfoEntity` |
| `signType` | `String` | 源自 `PayInfoEntity` |
| `keyPath` | `String` | 源自 `PayInfoEntity` |
| `keyContent` | `String` | 源自 `PayInfoEntity` |

响应 data 说明：
- 按 `JsonResult<PayInfoEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### POST `/wx/pay/info/update`

- 处理方法：`WxPayController.updatePayInfo`
- 响应：[JsonResult](#model-jsonresult)<[PayInfoEntity](#model-payinfoentity)>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `info` | body | yes | [PayInfoEntity](#model-payinfoentity) | 请求体 |

请求体字段：

[PayInfoEntity](#model-payinfoentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `PayInfoEntity` |
| `updateTime` | `Long` | 源自 `PayInfoEntity` |
| `tenantId` | `String` | 源自 `PayInfoEntity` |
| `clientId` | `String` | 源自 `PayInfoEntity` |
| `acid` | `Long` | 源自 `PayInfoEntity` |
| `name` | `String` | 源自 `PayInfoEntity` |
| `account` | `String` | 源自 `PayInfoEntity` |
| `country` | `String` | 源自 `PayInfoEntity` |
| `province` | `String` | 源自 `PayInfoEntity` |
| `city` | `String` | 源自 `PayInfoEntity` |
| `username` | `String` | 源自 `PayInfoEntity` |
| `password` | `String` | 源自 `PayInfoEntity` |
| `status` | `Integer` | 源自 `PayInfoEntity` |
| `appId` | `String` | 源自 `PayInfoEntity` |
| `subAppId` | `String` | 源自 `PayInfoEntity` |
| `mchId` | `String` | 源自 `PayInfoEntity` |
| `mchKey` | `String` | 源自 `PayInfoEntity` |
| `subMchId` | `String` | 源自 `PayInfoEntity` |
| `notifyUrl` | `String` | 源自 `PayInfoEntity` |
| `tradeType` | `String` | 源自 `PayInfoEntity` |
| `signType` | `String` | 源自 `PayInfoEntity` |
| `keyPath` | `String` | 源自 `PayInfoEntity` |
| `keyContent` | `String` | 源自 `PayInfoEntity` |

响应 data 说明：
- 按 `JsonResult<PayInfoEntity>` 返回；若是 `JsonResult<T>`，实际业务数据在 `data` 字段。

#### GET `/wx/pay/info/delete`

- 处理方法：`WxPayController.deletePayInfo`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | query | yes | Long | 查询/表单参数 |

响应 data 说明：
- 成功时通常无 `data` 或 `data=null`。

#### POST `/wx/pay/info/list`

- 处理方法：`WxPayController.listPayInfo`
- 响应：[JsonResult](#model-jsonresult)<Void>

| 参数名 | 位置 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | body | yes | JsonRequestPage<[PayInfoEntity](#model-payinfoentity)> | 请求体 |

请求体字段：

[PayInfoEntity](#model-payinfoentity)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `createTime` | `Long` | 源自 `PayInfoEntity` |
| `updateTime` | `Long` | 源自 `PayInfoEntity` |
| `tenantId` | `String` | 源自 `PayInfoEntity` |
| `clientId` | `String` | 源自 `PayInfoEntity` |
| `acid` | `Long` | 源自 `PayInfoEntity` |
| `name` | `String` | 源自 `PayInfoEntity` |
| `account` | `String` | 源自 `PayInfoEntity` |
| `country` | `String` | 源自 `PayInfoEntity` |
| `province` | `String` | 源自 `PayInfoEntity` |
| `city` | `String` | 源自 `PayInfoEntity` |
| `username` | `String` | 源自 `PayInfoEntity` |
| `password` | `String` | 源自 `PayInfoEntity` |
| `status` | `Integer` | 源自 `PayInfoEntity` |
| `appId` | `String` | 源自 `PayInfoEntity` |
| `subAppId` | `String` | 源自 `PayInfoEntity` |
| `mchId` | `String` | 源自 `PayInfoEntity` |
| `mchKey` | `String` | 源自 `PayInfoEntity` |
| `subMchId` | `String` | 源自 `PayInfoEntity` |
| `notifyUrl` | `String` | 源自 `PayInfoEntity` |
| `tradeType` | `String` | 源自 `PayInfoEntity` |
| `signType` | `String` | 源自 `PayInfoEntity` |
| `keyPath` | `String` | 源自 `PayInfoEntity` |
| `keyContent` | `String` | 源自 `PayInfoEntity` |

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

### Model MpInfoEntity

源码：`wx/jia-wx-core/src/main/java/cn/jia/wx/entity/MpInfoEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `acid` | `acid` | `Long` |
| `token` | `token` | `String` |
| `accessToken` | `accessToken` | `String` |
| `encodingaeskey` | `encodingaeskey` | `String` |
| `level` | `level` | `Integer` |
| `name` | `name` | `String` |
| `account` | `account` | `String` |
| `original` | `original` | `String` |
| `signature` | `signature` | `String` |
| `country` | `country` | `String` |
| `province` | `province` | `String` |
| `city` | `city` | `String` |
| `username` | `username` | `String` |
| `password` | `password` | `String` |
| `status` | `status` | `Integer` |
| `appid` | `appid` | `String` |
| `secret` | `secret` | `String` |
| `styleid` | `styleid` | `Integer` |
| `subscribeurl` | `subscribeurl` | `String` |
| `authRefreshToken` | `authRefreshToken` | `String` |

### Model MpInfoVO

源码：`wx/jia-wx-api/src/main/java/cn/jia/wx/entity/MpInfoVO.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `acid` | `acid` | `Long` |
| `token` | `token` | `String` |
| `accessToken` | `accessToken` | `String` |
| `encodingaeskey` | `encodingaeskey` | `String` |
| `level` | `level` | `Integer` |
| `name` | `name` | `String` |
| `account` | `account` | `String` |
| `original` | `original` | `String` |
| `signature` | `signature` | `String` |
| `country` | `country` | `String` |
| `province` | `province` | `String` |
| `city` | `city` | `String` |
| `username` | `username` | `String` |
| `password` | `password` | `String` |
| `status` | `status` | `Integer` |
| `appid` | `appid` | `String` |
| `secret` | `secret` | `String` |
| `styleid` | `styleid` | `Integer` |
| `subscribeurl` | `subscribeurl` | `String` |
| `authRefreshToken` | `authRefreshToken` | `String` |
| `clientIdList` | `clientIdList` | `List<String>` |

### Model PayInfoEntity

源码：`wx/jia-wx-core/src/main/java/cn/jia/wx/entity/PayInfoEntity.java`

| 字段 | Java 字段 | 类型 |
| --- | --- | --- |
| `createTime` | `createTime` | `Long` |
| `updateTime` | `updateTime` | `Long` |
| `tenantId` | `tenantId` | `String` |
| `clientId` | `clientId` | `String` |
| `acid` | `acid` | `Long` |
| `name` | `name` | `String` |
| `account` | `account` | `String` |
| `country` | `country` | `String` |
| `province` | `province` | `String` |
| `city` | `city` | `String` |
| `username` | `username` | `String` |
| `password` | `password` | `String` |
| `status` | `status` | `Integer` |
| `appId` | `appId` | `String` |
| `subAppId` | `subAppId` | `String` |
| `mchId` | `mchId` | `String` |
| `mchKey` | `mchKey` | `String` |
| `subMchId` | `subMchId` | `String` |
| `notifyUrl` | `notifyUrl` | `String` |
| `tradeType` | `tradeType` | `String` |
| `signType` | `signType` | `String` |
| `keyPath` | `keyPath` | `String` |
| `keyContent` | `keyContent` | `String` |
