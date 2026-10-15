# 第三方回调保护清单

核对日期2026-10-15；公共规范v1.2 §7.1。用户明确：微信、GitHub等第三方回调不能按CYF自有接口规范改写。本次仅源码识别、目标路由核对和文档标注，未修改应用/配置，未请求第三方，未完成官方协议逐字段验证。

## 已识别的直接Controller入口：10条

| 提供方/用途 | 现有入口 | 外部wire保护 |
| --- | --- | --- |
| 微信公众号接入握手 | GET /wx/mp/checksignature | 原query验签及challenge响应；不套R |
| 微信公众号消息 | POST /wx/mp/checksignature | 原query/原始消息、验签/解密、回复与ACK |
| 微信支付订单通知 | POST /wx/pay/parseOrderNotifyResult | 当前源码XML/SDK链，保留验签及支付ACK协议 |
| 微信退款通知 | POST /wx/pay/parseRefundNotifyResult | 当前源码XML/SDK链；现返回解析DTO的偏差仍待核定，不把DTO套R当修复 |
| 微信扫码支付通知 | POST /wx/pay/parseScanPayNotifyResult | 当前源码XML/SDK链；实际业务应答字段继续按提供方协议核定 |
| SMS提供方回调 | GET /sms/receive | 提供方身份/版本尚待确认；保持原method和参数，不因写入改POST |
| 微信公众号OAuth | GET /oauth/third-party/wxmp | 原code/state及浏览器重定向、state消费/恢复 |
| 微信开放平台OAuth | GET /oauth/third-party/weixin | 同上，不能变JSON业务接口 |
| 微博OAuth | GET /oauth/third-party/weibo | 原code/state及浏览器重定向 |
| GitHub OAuth | GET /oauth/third-party/github | 原code/state及浏览器重定向；LoginController已有对应redirect URI构造 |

上述10条在现目标路由表中method/path均未变。响应模式仍6条EXTERNAL_CALLBACK、4条HTML_OR_REDIRECT：模式是响应格式分类，**不能拿6当外部回调全集**。JSON清单补充独立externalContract标记，不把OAuth重定向改成普通JSON或重复算operation。

## 不能遗漏或误分类的入口

- `oauth/jia-oauth-client-starter/src/main/resources/application.yml`登记框架OAuth回调 `/login/oauth2/code/jia-client-oidc`，由依赖提供，未列入596条直接Controller库存。其实际启用/配置须另核，外部协议同样保护；不为补库存自行新增Controller。
- `/oauth/third-party/autologin`是源码中的本地登录续接，不是提供方直接回调；保留其HTML/redirect分类，是否退役依现登录事务设计处理，不因路径含third-party自动授予外部协议豁免。
- 本轮在已查Controller及OAuth配置中确认的是GitHub **OAuth回调**，不是GitHub webhook；没有据此声称全系统不存在webhook。其他运维服务/动态注册/部署回调继续按实际配置核对，发现后登记原协议，不能编造入口或补造服务。
- 微信模块中的订单查询、管理、配置等由Web/API调用的自有接口仍适用统一规范；外部SDK返回在本地管理HTTP中如何投影另行定义，不因名字wx/pay而整体排除。

## 实施要求与未决边界

1. 自有信封/异常处理/安全过滤入口排除需同时覆盖正常、验签失败、业务提交失败、重复通知和异常响应；按明确handler或框架匹配器识别，不用包含wx/github字符串的宽泛规则。
2. 外部raw body及签名相关query/header在验签前不做字段重命名、数值转换、JSON重序列化、空白规范化；内部领域适配在验证之后。OAuth使用原state绑定及重定向事务，不要求本站JWT或另一个Idempotency-Key。
3. ACK何时发送必须按提供方协议及已有事务设计核定，HTTP接收成功与业务提交成功不混同；不将外部ACK改成CYF E0，也不把内部错误对象直接泄给第三方。
4. 测试采用对应协议的完整原始报文、签名/加密fixture、重放与提交失败用例；不得拿Q/R的合成fixture验证这些入口。未执行此类测试，不声称回调已验收。
5. 现有退款/扫码ACK、SMS提供方身份、业务提交条件等继续保持未闭合。用户明确的是**外部契约不可被本次统一改写**，不是允许忽略缺陷，也不是要求保留错误实现。

## 2026-10-15续核：原始回执与内部提交的分界

源码继续核对，以下结论不冒充第三方官方协议逐字段认证：

- 订单通知：`WxPayController.parseOrderNotifyResult`按报文appid选SDK并parse验签；随后先写PayOrder.transactionId，再按TIP/GIF派发业务处理器。未找到订单也会返回SDK success，且只捕获WxPayException；普通持久化异常可能落入全局JSON错误。统一改造必须为该入口保留外部错误边界，不能把“SDK验签成功”当“订单金额/商户绑定和两步提交都已成立”。具体支付事务修复属于已有缺陷，不能借规范迁移自动新增退款/扣款业务。
- 退款通知：现方法只parse并返回SDK对象，没有业务退款落账。保护策略是排除通用Q/R；不得把现返回对象认定为已验收ACK，亦不得在本次核对中制造退款成功。
- 扫码通知：openid传给业务userId、处理器创建业务记录后再调用unifiedOrder；返回上游XML与应有回调字段是否一致仍待对应扫码模式协议确认，不能假造不存在的SDK响应类。
- SMS receive：源码顺序为分布式锁→按msgid查原send→精确回复去重→可选replyUrl GET转发→写本地reply。tenant/client取send，不取回调请求。但转发成功后、本地写入失败会导致重试再次转发，当前不是端到端exactly-once；锁持有期与外部调用也不能自动当业务幂等保证。现入口没有可见提供方签名参数；查到send只是业务关联，不是提供方认证。提供方/协议身份未确认，不能擅自强加JWT、改method或改原code/msg应答。
- OAuth四回调：`ThirdPartyLoginTransactionService`已有32随机字节base64url state、Redis5分钟待消费、getAndDelete原子消费，完成记录保留1分钟用于恢复原redirect。统一协议迁移保留这条链，不用内部requestId替换state、不重复交换已消费code；缺code/provider error的回调分支需按各提供方协议定义，而非落进通用Q解析错误。此处分钟数是**本地实现事实**，不是声称第三方统一有效期。

本轮尝试检索提供方文档，但工具未返回可保存的正文证据；因此未把支付ACK/错误分支标为已核定。外部协议保留原则已确定；实际缺陷、外部规范未确认项和通用wire迁移必须分别记录，不能互相冒充完成。
