# oauth：逐HTTP接口源码清单

基线 `7e2d07a05b9e9b18461e97abcff44642504320ff`；13 个method/path条目。源代码声明不是目标契约已完成。公共封装/权限/幂等见[目录说明](README.md)，人工复核关键链见[逐接口契约](critical-contracts.md)。

## `GET /token`

- operationId：`AuthenticationController.token.GET`
- 源码：`oauth/jia-oauth-client-starter/src/main/java/cn/jia/oauth/api/AuthenticationController.java:12`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public OAuth2AuthorizedClient token( @RegisteredOAuth2AuthorizedClient OAuth2AuthorizedClient oAuth2AuthorizedClient)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /resource`

- operationId：`OAuthResourceIdentityController.resource.GET`
- 源码：`oauth/jia-oauth-resource/src/main/java/cn/jia/oauth/api/OAuthResourceIdentityController.java:18`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<OAuthResourceIdentityDTO> resource(Authentication authentication)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /user/me/sessions/revoke-all`

- operationId：`SessionRevocationController.revokeAll.POST`
- 源码：`oauth/jia-oauth-resource/src/main/java/cn/jia/oauth/api/SessionRevocationController.java:25`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ResponseEntity<?> revokeAll(Authentication authentication, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /oauth/third-party/wxmp`

- operationId：`OauthController.thirdPartyWxMp.GET`
- 源码：`oauth/jia-oauth-service/src/main/java/cn/jia/oauth/api/OauthController.java:102`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public String thirdPartyWxMp(@RequestParam String code, @RequestParam String state, HttpServletRequest request, HttpServletResponse response)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /oauth/third-party/weixin`

- operationId：`OauthController.thirdPartyWeiXin.GET`
- 源码：`oauth/jia-oauth-service/src/main/java/cn/jia/oauth/api/OauthController.java:182`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public String thirdPartyWeiXin(@RequestParam String code, @RequestParam String state, HttpServletRequest request, HttpServletResponse response)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /oauth/third-party/weibo`

- operationId：`OauthController.thirdPartyWeiBo.GET`
- 源码：`oauth/jia-oauth-service/src/main/java/cn/jia/oauth/api/OauthController.java:234`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public String thirdPartyWeiBo(@RequestParam String code, @RequestParam String state, HttpServletRequest request, HttpServletResponse response)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /oauth/third-party/github`

- operationId：`OauthController.thirdPartyGithub.GET`
- 源码：`oauth/jia-oauth-service/src/main/java/cn/jia/oauth/api/OauthController.java:316`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public String thirdPartyGithub(@RequestParam String code, @RequestParam String state, HttpServletRequest request, HttpServletResponse response)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /oauth/third-party/autologin`

- operationId：`OauthController.thirdPartyAutoLogin.GET`
- 源码：`oauth/jia-oauth-service/src/main/java/cn/jia/oauth/api/OauthController.java:381`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public String thirdPartyAutoLogin(HttpServletRequest request, HttpServletResponse response)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /oauth/confirm_access`

- operationId：`OauthController.getAccessConfirmation.GET`
- 源码：`oauth/jia-oauth-service/src/main/java/cn/jia/oauth/api/OauthController.java:513`
- 标记：普通映射；内容类型/权限仍须验证
- 当前声明（含入参位置、Java类型及默认值）：

```java
public ModelAndView getAccessConfirmation( @RequestParam("client_id") String clientId, @RequestParam("state") String state, @RequestParam(name = "scope", required = false, defaultValue = "") String scope)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /oauth/clientid`

- operationId：`OauthController.findClientId.GET`
- 源码：`oauth/jia-oauth-service/src/main/java/cn/jia/oauth/api/OauthController.java:540`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object findClientId(@RequestParam(name = "appcn") String appcn)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /oauth/client/get`

- operationId：`OauthController.find.GET`
- 源码：`oauth/jia-oauth-service/src/main/java/cn/jia/oauth/api/OauthController.java:558`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object find()
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /oauth/client/update`

- operationId：`OauthController.updateClient.POST`
- 源码：`oauth/jia-oauth-service/src/main/java/cn/jia/oauth/api/OauthController.java:574`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object updateClient(@RequestBody OauthClientEntity client)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /oauth/user`

- operationId：`OauthController.info.GET`
- 源码：`oauth/jia-oauth-service/src/main/java/cn/jia/oauth/api/OauthController.java:591`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object info(Principal user)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

