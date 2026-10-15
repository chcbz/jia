# GET副作用候选逐项复核

日期2026-10-14；来自固定源码调用及路由名筛查。命中不等于已发生线上副作用；只读快照生成与空实现已单列，不能仅凭名称改成POST。表中目标方法是修改设计，不是已实施。

| 当前GET路径 | 实际调用线索 | 分类 | 目标方法 | 源码 |
| --- | --- | --- | --- | --- |
| `/agent/tasks/{taskId}/context-pack` | generate | READ_SNAPSHOT_GENERATION | GET | `agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskContextPackController.java:49` |
| `/dict/cleanCache` | update | MUTATION_CALL_CANDIDATE | POST | `base/jia-base-service/src/main/java/cn/jia/base/api/DictController.java:109` |
| `/car/brand/delete` | deleteBrand | MUTATION_CALL_CANDIDATE | DELETE | `isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:93` |
| `/car/brandAudi/delete` | deleteBrandAudi | MUTATION_CALL_CANDIDATE | DELETE | `isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:161` |
| `/car/brandVersion/delete` | deleteBrandVersion | MUTATION_CALL_CANDIDATE | DELETE | `isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:229` |
| `/car/brandMf/delete` | deleteBrandMf | MUTATION_CALL_CANDIDATE | DELETE | `isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:297` |
| `/cms/table/delete` | deleteTable | MUTATION_CALL_CANDIDATE | DELETE | `isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:105` |
| `/cms/column/delete` | deleteColumn | MUTATION_CALL_CANDIDATE | DELETE | `isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:174` |
| `/isp/server/delete` | deleteServer | MUTATION_CALL_CANDIDATE | DELETE | `isp/jia-isp-service/src/main/java/cn/jia/isp/api/IspController.java:105` |
| `/isp/server/ssl/refresh` | 空实现/待核 | NO_OP_PLACEHOLDER | POST（501） | `isp/jia-isp-service/src/main/java/cn/jia/isp/api/IspController.java:118` |
| `/isp/domain/record/delete` | 空实现/待核 | NO_OP_PLACEHOLDER | DELETE（501） | `isp/jia-isp-service/src/main/java/cn/jia/isp/api/IspController.java:124` |
| `/ldap/user/delete` | deleteLdapUser | MUTATION_CALL_CANDIDATE | DELETE | `isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:144` |
| `/ldap/usergroup/delete` | delete | MUTATION_CALL_CANDIDATE | DELETE | `isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:290` |
| `/kefu/faq/delete` | delete | MUTATION_CALL_CANDIDATE | DELETE | `kefu/jia-kefu-service/src/main/java/cn/jia/kefu/api/KefuController.java:119` |
| `/kefu/message/delete` | delete | MUTATION_CALL_CANDIDATE | DELETE | `kefu/jia-kefu-service/src/main/java/cn/jia/kefu/api/KefuController.java:208` |
| `/media/delete` | delete, delete | MUTATION_CALL_CANDIDATE | DELETE | `material/jia-material-service/src/main/java/cn/jia/mat/api/MediaController.java:105` |
| `/news/delete` | delete | MUTATION_CALL_CANDIDATE | DELETE | `material/jia-material-service/src/main/java/cn/jia/mat/api/NewsController.java:118` |
| `/news/send` | send | MUTATION_CALL_CANDIDATE | POST | `material/jia-material-service/src/main/java/cn/jia/mat/api/NewsController.java:153` |
| `/phrase/delete` | delete | MUTATION_CALL_CANDIDATE | DELETE | `material/jia-material-service/src/main/java/cn/jia/mat/api/PhraseController.java:62` |
| `/tip/delete` | delete | MUTATION_CALL_CANDIDATE | DELETE | `material/jia-material-service/src/main/java/cn/jia/mat/api/TipController.java:64` |
| `/vote/delete` | delete | MUTATION_CALL_CANDIDATE | DELETE | `material/jia-material-service/src/main/java/cn/jia/mat/api/VoteController.java:69` |
| `/sms/gen` | send | MUTATION_CALL_CANDIDATE | POST | `sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:92` |
| `/sms/template/delete` | deleteTemplate | MUTATION_CALL_CANDIDATE | DELETE | `sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:348` |
| `/login/sms/gen` | send | MUTATION_CALL_CANDIDATE | POST | `user/jia-user-service/src/main/java/cn/jia/user/api/LoginController.java:178` |
| `/login/user/password/change` | 空实现/待核 | MUTATION_CALL_CANDIDATE | POST | `user/jia-user-service/src/main/java/cn/jia/user/api/LoginController.java:403` |
| `/msg/read` | update | MUTATION_CALL_CANDIDATE | POST | `user/jia-user-service/src/main/java/cn/jia/user/api/MsgController.java:56` |
| `/msg/unread` | update | MUTATION_CALL_CANDIDATE | POST | `user/jia-user-service/src/main/java/cn/jia/user/api/MsgController.java:71` |
| `/user/point/change` | changePoint | MUTATION_CALL_CANDIDATE | POST | `user/jia-user-service/src/main/java/cn/jia/user/api/UserController.java:287` |
| `/user/password/change` | changePassword | MUTATION_CALL_CANDIDATE | POST | `user/jia-user-service/src/main/java/cn/jia/user/api/UserController.java:363` |
| `/user/password/reset` | resetPassword | MUTATION_CALL_CANDIDATE | POST | `user/jia-user-service/src/main/java/cn/jia/user/api/UserController.java:379` |
| `/user/position/change` | update | MUTATION_CALL_CANDIDATE | POST | `user/jia-user-service/src/main/java/cn/jia/user/api/UserController.java:422` |
| `/workflow/deployment/delete` | deleteDeployment | MUTATION_CALL_CANDIDATE | DELETE | `workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:152` |
| `/workflow/definition/activate` | activateProcessDefinition | MUTATION_CALL_CANDIDATE | POST | `workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:243` |
| `/workflow/definition/suspend` | suspendProcessDefinition | MUTATION_CALL_CANDIDATE | POST | `workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:255` |
| `/workflow/task/delegate` | delegateTask | MUTATION_CALL_CANDIDATE | POST | `workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:490` |
| `/workflow/task/claim` | claimTask | MUTATION_CALL_CANDIDATE | POST | `workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:502` |
| `/workflow/task/assign` | 空实现/待核 | MUTATION_CALL_CANDIDATE | POST | `workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:515` |
| `/wx/mp/info/delete` | delete | MUTATION_CALL_CANDIDATE | DELETE | `wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:1100` |
| `/wx/pay/createOrder` | create | MUTATION_CALL_CANDIDATE | POST /wx/pay/createOrder/product | `wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:132` |
| `/wx/pay/unifiedOrder` | create | MUTATION_CALL_CANDIDATE | POST /wx/pay/unifiedOrder/product | `wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:260` |
| `/wx/pay/info/delete` | delete | MUTATION_CALL_CANDIDATE | DELETE | `wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:723` |

涉及密码/验证码的自有JSON动作必须把秘密从query移入data，原认证/校验不删。无持久幂等能力的旧动作不能仅收Idempotency-Key就谎报幂等：须明确非自动重试，重复/结果未知行为在逐接口核定中闭合；不得顺带添加通用扣费或幂等平台。

续查补漏（共42候选）：GET `/wx/pay/closeOrder/{outTradeNo}`在WxPayController.java:105调用closeOrder，目标POST原路径。两条product路由改名避免与既有SDK POST撞路由；全量目标表见`target-route-audit.json`。

## 嵌套写入复核补漏（review5）

原42候选基础上新增4条，共46条；这是已查集合，不表示所有GET调用树已闭合。

| 当前GET路径 | 实际调用线索 | 分类 | 目标方法 | 源码 |
| --- | --- | --- | --- | --- |
| `/dwz/restore` | view → updateById | NESTED_WRITE_SOURCE_CONFIRMED | POST | `dwz/jia-dwz-service/src/main/java/cn/jia/dwz/api/DwzController.java:90` |
| `/msg/readall` | readAll → updateByUserId | NESTED_WRITE_SOURCE_CONFIRMED | POST | `user/jia-user-service/src/main/java/cn/jia/user/api/MsgController.java:86` |
| `/msg/recycle` | recycle → updateById | NESTED_WRITE_SOURCE_CONFIRMED | POST | `user/jia-user-service/src/main/java/cn/jia/user/api/MsgController.java:111` |
| `/msg/restore` | restore → updateById | NESTED_WRITE_SOURCE_CONFIRMED | POST | `user/jia-user-service/src/main/java/cn/jia/user/api/MsgController.java:123` |

公开导航`GET /dwz/view/{uri:.+}/**`仍保持重定向：访问计数是导航附带统计，不属于将JSON写动作改POST的四条，也不计入上述46候选。四条新POST保留原query参数，空业务body为Q<null>；`readall.userId`只能匹配可信用户，不可用于跨用户选择。

## 全量GET入口复查补漏（2026-10-15 review6）

在此前46条已查候选基础上新增4条，累计50条。这里只确认所列写入；不宣称跨外部SDK的全部调用树已闭合。

| 当前GET路径 | 实际调用线索 | 分类 | 目标方法 | 源码 |
| --- | --- | --- | --- | --- |
| `/phrase/read` | read → updateById | WRITE_SOURCE_CONFIRMED | POST | `material/jia-material-service/src/main/java/cn/jia/mat/api/PhraseController.java:101` |
| `/sms/validate` | useSmsCode | WRITE_SOURCE_CONFIRMED | POST | `sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:53` |
| `/sms/use` | useSmsCode | WRITE_SOURCE_CONFIRMED | POST | `sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:72` |
| `/sms/buy` | buy → insert/updateById | WRITE_SOURCE_CONFIRMED | POST | `sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:381` |

phrase/read为独立计数动作，不是图片/HTML资源读取。sms/validate、sms/use均消耗验证码；phone/smsType/smsCode移到Q.data，不能继续把验证码写URL。sms/buy更新余额与购买记录，number及金额迁入Q.data；金额单位/授权须依领域核定，不凭double直接定CNY分。media/thumbnail的衍生文件缓存保留GET，短信receive为外部回调候选，须单列provider边界，不能机械改POST。
