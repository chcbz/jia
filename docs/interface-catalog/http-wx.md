# wx：逐HTTP接口源码清单

基线 `7e2d07a05b9e9b18461e97abcff44642504320ff`；57 个method/path条目。源代码声明不是目标契约已完成。公共封装/权限/幂等见[目录说明](README.md)，人工复核关键链见[逐接口契约](critical-contracts.md)。

## `GET /wx/mp/checksignature`

- operationId：`WxMpController.checkSignature.GET`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:134`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object checkSignature(@RequestParam("signature") String signature, @RequestParam("nonce") String nonce, @RequestParam("timestamp") String timestamp, @RequestParam("echostr") String echostr, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /wx/mp/checksignature`

- operationId：`WxMpController.receiveMsg.POST`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:153`
- 标记：RAW_OR_DYNAMIC_BODY, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object receiveMsg(@RequestBody String msg, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `ANY /wx/mp/menu/create`

- operationId：`WxMpController.menuCreate.ANY`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:700`
- 标记：RAW_OR_DYNAMIC_BODY, METHOD_UNRESTRICTED, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object menuCreate(@RequestBody String menu, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `ANY /wx/mp/menu/get`

- operationId：`WxMpController.menuGet.ANY`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:715`
- 标记：METHOD_UNRESTRICTED, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object menuGet(HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `ANY /wx/mp/menu/delete`

- operationId：`WxMpController.menuDelete.ANY`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:727`
- 标记：METHOD_UNRESTRICTED, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object menuDelete(HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `ANY /wx/mp/user/get`

- operationId：`WxMpController.userGet.ANY`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:741`
- 标记：METHOD_UNRESTRICTED, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object userGet(@RequestParam(value = "next_openid", required = false) String nextOpenid, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `ANY /wx/mp/user/info/batchget`

- operationId：`WxMpController.userInfoBatchGet.ANY`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:754`
- 标记：RAW_OR_DYNAMIC_BODY, METHOD_UNRESTRICTED, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object userInfoBatchGet(@RequestBody List<String> openids, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `ANY /wx/mp/user/sync`

- operationId：`WxMpController.userSync.ANY`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:766`
- 标记：METHOD_UNRESTRICTED, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object userSync(HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `ANY /wx/mp/oauth2/authorize_url`

- operationId：`WxMpController.oauth2Authorize.ANY`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:819`
- 标记：METHOD_UNRESTRICTED, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object oauth2Authorize(@RequestParam("redirect_uri") String redirectUri, @RequestParam("scope") String scope, @RequestParam("state") String state, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `ANY /wx/mp/oauth2/access_token`

- operationId：`WxMpController.oauth2AccessToken.ANY`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:834`
- 标记：METHOD_UNRESTRICTED, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object oauth2AccessToken(@RequestParam("code") String code, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `ANY /wx/mp/oauth2/userinfo`

- operationId：`WxMpController.oauth2Userinfo.ANY`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:849`
- 标记：METHOD_UNRESTRICTED, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object oauth2Userinfo(@RequestParam("access_token") String accessToken, @RequestParam("openid") String openId, @RequestParam("lang") String lang, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `ANY /wx/mp/oauth2/refresh_token`

- operationId：`WxMpController.oauth2RefreshToken.ANY`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:868`
- 标记：METHOD_UNRESTRICTED, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object oauth2RefreshToken(@RequestParam("refresh_token") String refreshToken, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `ANY /wx/mp/oauth2/auth`

- operationId：`WxMpController.oauth2ValidateAccessToken.ANY`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:883`
- 标记：METHOD_UNRESTRICTED, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object oauth2ValidateAccessToken(@RequestParam("access_token") String accessToken, @RequestParam("openid") String openId, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `ANY /wx/mp/jsapi/signature`

- operationId：`WxMpController.createJsapiSignature.ANY`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:900`
- 标记：METHOD_UNRESTRICTED, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object createJsapiSignature(@RequestParam("url") String url, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `ANY /wx/mp/customservice/getkflist`

- operationId：`WxMpController.getkflist.ANY`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:912`
- 标记：METHOD_UNRESTRICTED, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object getkflist(HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `ANY /wx/mp/customservice/kfsession/getwaitcase`

- operationId：`WxMpController.getwaitcase.ANY`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:924`
- 标记：METHOD_UNRESTRICTED, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object getwaitcase(HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `ANY /wx/mp/message/custom/send`

- operationId：`WxMpController.customMessageSend.ANY`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:937`
- 标记：METHOD_UNRESTRICTED, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object customMessageSend(@RequestBody WxMpKefuMessage message, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `ANY /wx/mp/message/template/send`

- operationId：`WxMpController.templateMessageSend.ANY`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:950`
- 标记：METHOD_UNRESTRICTED, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object templateMessageSend(@RequestBody WxMpTemplateMessage templateMessage, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `ANY /wx/mp/material/batchget`

- operationId：`WxMpController.materialBatchGet.ANY`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:965`
- 标记：METHOD_UNRESTRICTED, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object materialBatchGet(@RequestParam("type") String type, @RequestParam("offset") Integer offset, @RequestParam("count") Integer count, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `ANY /wx/mp/material/add_news`

- operationId：`WxMpController.materialAddNews.ANY`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:984`
- 标记：METHOD_UNRESTRICTED, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object materialAddNews(@RequestBody WxMpMaterialNews news, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `ANY /wx/mp/material/add_material`

- operationId：`WxMpController.materialAdd.ANY`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:1035`
- 标记：MULTIPART, METHOD_UNRESTRICTED, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object materialAdd(@RequestPart(required = false, value = "file") MultipartFile file, @RequestParam(value = "type") String type, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /wx/mp/info/get`

- operationId：`WxMpController.findMpInfoById.GET`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:1060`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object findMpInfoById(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /wx/mp/info/create`

- operationId：`WxMpController.createMpInfo.POST`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:1075`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object createMpInfo(@RequestBody MpInfoEntity info)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /wx/mp/info/update`

- operationId：`WxMpController.updateMpInfo.POST`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:1088`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object updateMpInfo(@RequestBody MpInfoEntity info)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /wx/mp/info/delete`

- operationId：`WxMpController.deleteMpInfo.GET`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:1100`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object deleteMpInfo(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /wx/mp/info/list`

- operationId：`WxMpController.listMpInfo.POST`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:1113`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object listMpInfo(@RequestBody JsonRequestPage<MpInfoVO> page, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /wx/pay/queryOrder`

- operationId：`WxPayController.queryOrder.GET`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:83`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public WxPayOrderQueryResult queryOrder(@RequestParam(required = false) String transactionId, @RequestParam(required = false) String outTradeNo, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /wx/pay/closeOrder/{outTradeNo}`

- operationId：`WxPayController.closeOrder.GET`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:105`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public WxPayOrderCloseResult closeOrder(@PathVariable String outTradeNo, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /wx/pay/createOrder`

- operationId：`WxPayController.createOrder.POST`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:116`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public <T> T createOrder(@RequestBody WxPayUnifiedOrderRequest request, HttpServletRequest req)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /wx/pay/createOrder`

- operationId：`WxPayController.createOrder.GET`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:132`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public <T> T createOrder(@RequestParam(required = false) String productId, @RequestParam(required = false) String jiacn, @RequestParam(required = false) String outTradeNo, @RequestParam String tradeType, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /wx/pay/unifiedOrder`

- operationId：`WxPayController.unifiedOrder.POST`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:246`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public WxPayUnifiedOrderResult unifiedOrder(@RequestBody WxPayUnifiedOrderRequest request, HttpServletRequest req)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /wx/pay/unifiedOrder`

- operationId：`WxPayController.unifiedOrder.GET`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:260`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public WxPayUnifiedOrderResult unifiedOrder(@RequestParam String productId, @RequestParam String openid, @RequestParam String tradeType, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /wx/pay/refund`

- operationId：`WxPayController.refund.POST`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:304`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public WxPayRefundResult refund(@RequestBody WxPayRefundRequest request, HttpServletRequest req)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /wx/pay/refundQuery`

- operationId：`WxPayController.refundQuery.GET`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:332`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public WxPayRefundQueryResult refundQuery(@RequestParam(required = false) String transactionId, @RequestParam(required = false) String outTradeNo, @RequestParam(required = false) String outRefundNo, @RequestParam(required = false) String refundId, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /wx/pay/parseOrderNotifyResult`

- operationId：`WxPayController.parseOrderNotifyResult.POST`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:344`
- 标记：RAW_OR_DYNAMIC_BODY, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object parseOrderNotifyResult(@RequestBody String xmlData)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /wx/pay/parseRefundNotifyResult`

- operationId：`WxPayController.parseRefundNotifyResult.POST`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:374`
- 标记：RAW_OR_DYNAMIC_BODY
- 当前声明（含入参位置、Java类型及默认值）：

```java
public WxPayRefundNotifyResult parseRefundNotifyResult(@RequestBody String xmlData, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /wx/pay/parseScanPayNotifyResult`

- operationId：`WxPayController.parseScanPayNotifyResult.POST`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:386`
- 标记：RAW_OR_DYNAMIC_BODY, DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object parseScanPayNotifyResult(@RequestBody String xmlData, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /wx/pay/sendRedpack`

- operationId：`WxPayController.sendRedpack.POST`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:437`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public WxPaySendRedpackResult sendRedpack(@RequestBody WxPaySendRedpackRequest request, HttpServletRequest req)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /wx/pay/queryRedpack/{mchBillNo}`

- operationId：`WxPayController.queryRedpack.GET`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:455`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public WxPayRedpackQueryResult queryRedpack(@PathVariable String mchBillNo, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /wx/pay/entPay`

- operationId：`WxPayController.entPay.POST`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:474`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public EntPayResult entPay(@RequestBody EntPayRequest request, HttpServletRequest req)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /wx/pay/queryEntPay/{partnerTradeNo}`

- operationId：`WxPayController.queryEntPay.GET`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:491`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public EntPayQueryResult queryEntPay(@PathVariable String partnerTradeNo, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /wx/pay/scanPay/qrcode`

- operationId：`WxPayController.createScanPayQrcodeMode1.POST`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:514`
- 标记：BYTE_RESPONSE_REQUIRES_MEDIA_CHECK, MULTIPART
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<byte[]> createScanPayQrcodeMode1(@RequestParam String productId, @RequestPart(required = false) MultipartFile logoFile, @RequestParam(required = false) Integer sideLength, HttpServletRequest request)
```

- 目标封装规则：返回byte[]不等于二进制文件：先核实际Content-Type/序列化。若为自有application/json仍必须统一信封；真实文件才走字节例外。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /wx/pay/scanPay/qrcodeLink`

- operationId：`WxPayController.createScanPayQrcodeMode1.GET`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:542`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public String createScanPayQrcodeMode1(@RequestParam String productId, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /wx/pay/report`

- operationId：`WxPayController.report.POST`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:582`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public void report(@RequestBody WxPayReportRequest request, HttpServletRequest req)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /wx/pay/downloadBill`

- operationId：`WxPayController.downloadBill.GET`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:613`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public WxPayBillResult downloadBill(@RequestParam String billDate, @RequestParam String billType, @RequestParam String tarType, @RequestParam String deviceInfo, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /wx/pay/micropay`

- operationId：`WxPayController.micropay.POST`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:632`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public WxPayMicropayResult micropay(@RequestBody WxPayMicropayRequest request, HttpServletRequest req)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /wx/pay/reverseOrder`

- operationId：`WxPayController.reverseOrder.POST`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:650`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public WxPayOrderReverseResult reverseOrder(@RequestBody WxPayOrderReverseRequest request, HttpServletRequest req)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /wx/pay/getSandboxSignKey`

- operationId：`WxPayController.getSandboxSignKey.GET`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:655`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public String getSandboxSignKey(HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /wx/pay/sendCoupon`

- operationId：`WxPayController.sendCoupon.POST`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:660`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public WxPayCouponSendResult sendCoupon(@RequestBody WxPayCouponSendRequest request, HttpServletRequest req)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /wx/pay/queryCouponStock`

- operationId：`WxPayController.queryCouponStock.POST`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:665`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public WxPayCouponStockQueryResult queryCouponStock(@RequestBody WxPayCouponStockQueryRequest request, HttpServletRequest req)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /wx/pay/queryCouponInfo`

- operationId：`WxPayController.queryCouponInfo.POST`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:671`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public WxPayCouponInfoQueryResult queryCouponInfo(@RequestBody WxPayCouponInfoQueryRequest request, HttpServletRequest req)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /wx/pay/queryComment`

- operationId：`WxPayController.queryComment.POST`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:677`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public String queryComment(Date beginDate, Date endDate, Integer offset, Integer limit)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /wx/pay/info/get`

- operationId：`WxPayController.findPayInfoById.GET`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:687`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object findPayInfoById(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /wx/pay/info/create`

- operationId：`WxPayController.createPayInfo.POST`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:701`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object createPayInfo(@RequestBody PayInfoEntity info)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /wx/pay/info/update`

- operationId：`WxPayController.updatePayInfo.POST`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:712`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object updatePayInfo(@RequestBody PayInfoEntity info)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /wx/pay/info/delete`

- operationId：`WxPayController.deletePayInfo.GET`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:723`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object deletePayInfo(@RequestParam(name = "id") Long id)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /wx/pay/info/list`

- operationId：`WxPayController.listPayInfo.POST`
- 源码：`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:734`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object listPayInfo(@RequestBody JsonRequestPage<PayInfoEntity> page)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

