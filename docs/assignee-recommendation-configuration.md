# 推荐承办模型与耗时（2026-11-03）

推荐仍通过项目ChatModel及现有计时包装调用，单次、无工具、无记忆。以下仅为此推荐请求复制并覆盖options，不改变共享默认项，不增加连接/API key副本：

```properties
# 留空继承全局model；非空必须是当前网关支持的模型ID。
jia.chat.recommendation.model=gpt-6-luna
# 留空继承默认；当前OpenAI适配器支持，值需与所选模型/网关兼容。
jia.chat.recommendation.reasoning-effort=
```

生产模型选择统一维护在`starter/src/main/resources/application-prod.properties`，随正式Flow构建进入JAR；不再通过主机`/home/isp/hosts/cyf/api/application.properties`维护聊天/推荐模型覆盖，不新增secret。通用适配器在未设置时行为不变；生产配置按用户选择显式使用`gpt-6-luna`，不自动选择新模型；设置不支持的推理参数由推荐适配器/Provider报错，不悄悄回退或自动重试。测试本地模拟HTTP证明请求字段和共享默认隔离，不证明真实网关模型可用或速度。

## 模型外耗时

目标为完整推荐请求除模型调用以外<3000ms，不作为请求强制超时。`assignee_recommendation_timing`输出服务总耗时、模型调用耗时、模型外、前置与后置耗时（单调时钟，含失败调用）；`assignee_recommendation_observe`区分候选投影和技能证据。日志不含Prompt、资料名、用户身份、响应正文或凭据。HTTP总时长仍由现有接口观测覆盖；服务计时不包含入口过滤和最终HTTP序列化，验收应结合相同请求的HTTP日志，不能仅凭服务计时声称全链达标。

推荐只读任务必要字段，保留原owner授权和标题/描述来源；候选及技能版本读取使用不计算历史任务统计的能力投影，原完整展示不变；推荐技能每次快照内只执行一次版本归属读取。保留模型前后任务版本/候选能力/附件关联版本复核、实时会话与目录一致性判断，不跨请求缓存权限，不持有数据库事务等待模型。

验收必须记录真实候选/技能/附件数量，区分成功/409和冷/热请求，以真实持久化及HTTP耗时扣除同请求模型计时核验。mock测试只证明调用链和规则，不作为3秒实测证据；本轮源码改动不代表已上线或已达标。

## 2026-11-03 全部外置配置收回JAR

按用户明确要求，将主机`/home/isp/hosts/cyf/api/application.properties`的全部122个配置项收回`starter/src/main/resources/application-prod.properties`，相同键以原外置有效值为准，不只迁移模型键。生产全局模型保持`gpt-6.1-sol`；推荐专用模型设为`gpt-6-luna`，推理强度留空继承。原embedding加密凭据保持ENC密文，不解密、不新增或复制推荐专用key。

包含原外置业务开关、存储目录、租户/身份/授权、embedding等配置的原值；此次只迁移配置来源，不增加授权或执行迁移。此生产配置绑定当前环境，不能把该JAR的prod配置直接用于其他租户或主机。源码/构建不等于线上切换。

上线使用固定Flow制品和现有受控安装器。安装前核对外置文件摘要仍等于已封装快照；先在原外置配置下安装并确认新包健康，再在现有发布锁与生命周期锁内备份并删除整个外置application.properties、重启核验包内配置。若删除后的启动/健康失败，恢复原文件的完整内容及权限再重启，保留失败证据；回退旧JAR时须同时恢复旧外置配置。其他外置文件、身份/state和数据库不清理。

验收：制品中全部122项与原外置有效值一致、推荐模型为gpt-6-luna、运行JAR摘要及来源Run匹配、原路径不存在、实际服务健康。原始配置快照保留受限备份目录；报告只记键名/数量/摘要，禁止输出凭据值。真实推荐端到端性能另行测量，不把本次配置归并当成模型外3秒达标。

## 2026-11-03 精简模型投影（源码候选，非上线状态）
- 单次请求以A1/A2及A1E1/A2E1替代真实Agent/证据ID，只允许精确映射，不兼容模型返回旧真实ID。HTTP响应仍是服务器原始Agent及证据；Web/Runtime无合同变化。
- 模型只接收任务、候选短编号/归属/可引用能力、资料名称/原文件名/MIME/角色/摘要。不发送重复本领、供应方名、内部运行状态、附件版本或文件状态。用途描述不截断，全部候选保留。
- 完整候选（含原技能版本/运行状态）及附件观察仍前后比较，投影精简不缩减权限或过期检查；模型外目标不作为强制超时。
- 未知/重复/不可操作候选分别记录CANDIDATE_UNKNOWN/CANDIDATE_DUPLICATE/CANDIDATE_NOT_OPERABLE，失败范围仍为本次推荐502。防止伪造、重复和跨候选证据，保留完整覆盖/证据可用性校验，不记录模型正文/标识，不自动重试。
- 定向回归：AssigneeRecommendationServiceTest、RecommendationMaterialsTest、AssigneeRecommendationModelTest、AssigneeRecommendationControllerTest。Luna及默认推理参数保持不变；无新增SDK结构化输出选项。
