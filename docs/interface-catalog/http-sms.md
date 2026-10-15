# sms：逐HTTP接口源码清单

基线 `7e2d07a05b9e9b18461e97abcff44642504320ff`；17 个method/path条目。源代码声明不是目标契约已完成。公共封装/权限/幂等见[目录说明](README.md)，人工复核关键链见[逐接口契约](critical-contracts.md)。

## `GET /sms/validate`

- **2026-10-15 review6目标：POST原路径，phone/smsType/smsCode移Q.data；这是消耗验证码动作，不是只读。**

- operationId：`SmsController.validateSmsCode.GET`
- 源码：`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:53`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object validateSmsCode(@RequestParam String phone, @RequestParam Integer smsType, @RequestParam String smsCode)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /sms/use`

- **2026-10-15 review6目标：POST原路径，phone/smsType移Q.data；秘密提取授权待核，不能通用缓存。**

- operationId：`SmsController.useSmsCode.GET`
- 源码：`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:72`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object useSmsCode(@RequestParam String phone, @RequestParam Integer smsType)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /sms/gen`

- operationId：`SmsController.gen.GET`
- 源码：`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:92`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object gen(@RequestParam String phone, @RequestParam Integer smsType, @RequestParam(value = "templateId", required = false) String templateId)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /sms/send`

- operationId：`SmsController.sendSms.POST`
- 源码：`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:137`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object sendSms(@RequestParam String mobile, @RequestParam String content, @RequestParam(required = false) String xh)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /sms/send/list`

- operationId：`SmsController.listSend.POST`
- 源码：`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:172`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object listSend(@RequestBody JsonRequestPage<SmsSendVO> page, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /sms/sendBatch`

- operationId：`SmsController.sendSmsBatch.POST`
- 源码：`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:190`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object sendSmsBatch(@RequestBody List<SmsBatchRecord> records)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /sms/balance`

- operationId：`SmsController.balance.GET`
- 源码：`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:225`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object balance()
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /sms/receive`

- **2026-10-15 review6目标：EXTERNAL_CALLBACK；保留provider原始请求及ack边界，不自动改GET或套新信封；具体认证/ack待核。**

- operationId：`SmsController.receive.GET`
- 源码：`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:242`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object receive(@RequestParam String mobile, @RequestParam String content, @RequestParam String msgid, @RequestParam(required = false) String xh)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /sms/reply/list`

- operationId：`SmsController.listReply.POST`
- 源码：`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:262`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object listReply(@RequestBody JsonRequestPage<SmsReplyEntity> page, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /sms/config/get`

- operationId：`SmsController.findConfig.GET`
- 源码：`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:278`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object findConfig()
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /sms/config/update`

- operationId：`SmsController.updateConfig.POST`
- 源码：`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:291`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object updateConfig(@RequestBody SmsConfigEntity config)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /sms/template/get`

- operationId：`SmsController.findTemplateById.GET`
- 源码：`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:306`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object findTemplateById(@RequestParam(name = "templateId") String templateId)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /sms/template/create`

- operationId：`SmsController.createTemplate.POST`
- 源码：`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:322`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object createTemplate(@RequestBody SmsTemplateEntity sms)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /sms/template/update`

- operationId：`SmsController.updateTemplate.POST`
- 源码：`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:335`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object updateTemplate(@RequestBody SmsTemplateEntity sms)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /sms/template/delete`

- operationId：`SmsController.deleteTemplate.GET`
- 源码：`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:348`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object deleteTemplate(@RequestParam(name = "templateId") String templateId)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `POST /sms/template/list`

- operationId：`SmsController.listTemplate.POST`
- 源码：`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:362`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object listTemplate(@RequestBody JsonRequestPage<SmsTemplateVO> page, HttpServletRequest request)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

## `GET /sms/buy`

- **2026-10-15 review6目标：POST原路径；条数/金额移Q.data，单位/币种未闭合，不猜转换。**

- operationId：`SmsController.buy.GET`
- 源码：`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:381`
- 标记：DYNAMIC_RETURN
- 当前声明（含入参位置、Java类型及默认值）：

```java
public Object buy(@RequestParam Integer number, @RequestParam Double money)
```

- 目标封装规则：自有JSON按公共信封；现行@RequestBody业务参数移至data；path/query/header位置不变；返回值为统一data中的业务DTO。外部协议/视图出口须人工分类，不能直接按本条批量改。
- 未核定：嵌套DTO完整约束、实际认证链/资源ACL、全部错误码/状态、幂等持久键、实际消费者与条件装配。**此条不作为可发布完整契约**；关键链人工定义优先见上述链接。

