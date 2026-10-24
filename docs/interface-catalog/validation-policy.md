# 校验必要性与兼容规格（2026-10-24）

本规格属于统一接口规范v1.4，适用于CYF API/Web/Runtime及本项目发布工具。不是新校验器、框架或审批阶段，也不代表所有存量校验已经逐条核实。

## 1. 判定规则

用户要求：未写清必要性的校验，不得默认认定必要。已有代码、旧测试和“安全起见”都不是充分依据。每项保留的业务拒绝至少说明：保护的具体事实、输入/条件、失败范围和行为、实现及回归入口。没有依据的性能数字、字段数量、应用版本一致性或重复检查应移除；尚未完成核实的项明确记为待核实，不能借补一句“安全检查”转成已批准。

同一事实只在实际信任/事务边界核验，不在同一个可信调用链机械重复。客户端可做用户提示，服务端授权和事务校验不能因前端已检查而移除。已缓存不可变制品可复用其验证结果，但不能跨内容、主体、版本或会话复用。

## 2. 本轮取消的限制（明确实现范围）

| 范围/代码入口 | 删除什么 | 保留及输出行为 |
| --- | --- | --- |
| HTTP Q/R：API `ApiJsonInput`/`JsonRequest`；Web `apiEnvelope.js`；Runtime engine/host `api-envelope.mjs` | 外层多一个字段即拒绝 | 仍必须有现行必需字段/类型、支持的语义版本、请求关联；扩展元数据忽略，不作为身份/授权；API保留原data字节；嵌套业务写DTO仍按其合同 |
| 异步M：API `ProtocolMessage.decode`，Web/engine/host `message-envelope` | 外层字段总数精确相等 | 已知事件/回复字段投影；扩展type/channel不能替代messageType，不下传为权限；回复三字段必须完整 |
| Web `hallAssigneeCandidates` | 候选/分页多字段导致无法点将 | 只输出已知展示字段；保留Agent ID、必需字段、去重、游标前进/尾项关联 |
| Web `hallSkillCapabilities`/`useHallAgentProfileCapabilities` | 目录/技能/费用说明多字段导致隐藏技能 | 仅投影已知字段；Agent绑定、就绪状态、费用类型不放宽；未知费用不是免费，目录不是执行授权 |
| Web `commandObservabilityPolicy`/`economyPreviewCapability` | 能力只读响应多字段导致整页不可用 | 保留明确可用/只读/身份范围；新增字段不授予写操作；不改变更深层资金操作合同 |
| Web `silverAmount.isSilverMoney` | Money展示多元数据就拒绝 | 只读取amountMinor/currency；Long精度、非负、币种保留；amountMicro不是amountMinor的替代字段 |
| Runtime `skill-conversation`及native/controlled-image/v3、`pollWorkspaceFileCommands` | 服务端已返回批次超过16条或多分页元数据就拒绝 | 仍检查items为数组、逐命令验证、租约/去重/主体隔离；服务端分页策略不变，不取消文件体积或Provider输入合同 |
| Runtime `skill-conversation`租约/副作用ACK/提交ACK | 多个无害回执字段即判未确认 | 保留executionId/token/version/expiry及结果摘要/状态关联，ACK只投影已知字段 |
| Runtime `conversation-text-file.test` | 测试将应用版本写死为1.2.0 | 保留执行器/安装包文件闭合检查；应用版本追溯由制品清单负责，不在功能测试固定旧发行号 |
| Runtime历史扫描（前轮b77f7d0） | 已完成历史记录被新执行DTO拒绝；单坏记录阻塞新任务 | 只读历史身份/摘要/提交事实；未决缺授权仅隔离对应execution，不重放UNKNOWN，不改原字节 |

上述兼容是允许扩展字段，不是兼容未知执行语义、大小写猜测、字段别名或自动填充授权。生产者仍输出最小现行字段；不主动制造扩展字段。

## 3. 已核实需要保留的规则

| 必要规则及原因 | 精确失败条件/失败行为 | 实现与回归入口 |
| --- | --- | --- |
| 外部JSON不应在不同消费者变成不同指令 | 重复key（含转义后同名）、尾随JSON、必需字段缺失/类型错误拒绝；不把损坏结果当成功 | `ApiJsonInput`、`ApiEnvelopeJsonConfig`、各端`parseStrictJson`；`ApiEnvelopeBoundaryTest`、`interface-envelope.test.mjs` |
| 请求和回复必须属于同一次请求 | requestId与请求/响应头不一致，或HTTP非成功却code=E0，拒绝；401/403仍作为实际失败 | `decodeHttpResponse`、`ApiJsonInput.bind`；上述测试及`interface-runtime-http.test.mjs` |
| 当前不理解的协议不能猜测执行副作用 | protocolVersion不等于已实现1、执行命令不等于4，拒绝该消息；不以Runtime/app发布版本号为门禁 | `ProtocolMessage`、`parseSkillCommand`；`ProtocolMessageTest`、`skill-executor.test.mjs`。新增无害字段不升级协议版本 |
| 不得跨用户/Agent/租户读取或执行 | 认证无效、资源主体不符、grant已撤销/代际变化，401/403或该领域明确拒绝；旧UI请求在主体切换后失效 | `nativeSessionHeaders`、`SkillConversationLane`、`PersonalWorkspaceExecutionServiceImpl`、Web身份epoch；`interface-agent-ws`、`skill-conversation`、`juyiting-agent-profile-capabilities`及后端权限专项 |
| 防止用旧授权、旧包或旧租约执行新动作 | TaskSkillBinding执行ID/包/manifest/批准权限不一致，lease token/version/expiry无效拒绝；绑定封闭字段用于明确批准权限，不接受未知授权语义 | `skill-executor`、`skill-registry`、`SkillConversationLane`；`skill-executor`/`skill-conversation`原负例 |
| 防重复调用模型/扣费和重复提交 | 已记录START但结果UNKNOWN，不再发起新效果；同execution换commandDigest为冲突；保持原key/副作用记录，只读核对 | `SkillEffects`、`SkillExecutionStore`、安装/报价事务；`skill-execution-store`、`skill-conversation`及购买幂等回归 |
| 防止读取错误资料或把未提交结果当交付 | inputRef/sha/byteLength/授权版本或提交摘要不匹配拒绝；真实bytes/回执不能由模型文字自证 | `validateSkillInputReceipts`、`parseSkillInputSnapshot`、`validateCommittedSkillResult`；`skill-executor`/`skill-execution-store`，资料授权专项 |
| 防路径逃逸和篡改本地恢复事实 | 非归属用户/过宽权限/软硬链接/读取中变更/identity摘要冲突拒绝共享扫描；单记录格式坏仅隔离并报告 | `readPrivate`、`privateDirectory`、`readSkillRecoveryCheckpoint`；`skill-conversation`、`skill-execution-store` |
| 防资金精度和购买意图改变 | 金额为规范非负十进制字符串且≤Java Long；币种正确；报价/批准权限/CAS/原key不可被新字段覆盖；失败不能默认0或再扣一次 | `silverAmount`、`skillManagementContract`写DTO、API技能报价/创建事务；`interface-money`、`ApiEnvelopeJsonConfigTest`及管理交易专项 |
| 防分页死循环与旧请求覆盖新UI | 游标不前进/重复ID/错误尾项，停止该分页并提示；主体或epoch变化丢弃旧响应 | `assigneePage`、`normalizeDlqPage`、各read composable；`juyiting-external-commission`、`command-observability` |
| 预览/Provider必须真实支持数据 | 不能展示不等于禁止存储；解码或Provider输入不支持应只拒绝该处理路径，不新增存储扩展名白名单 | 资料存储与预览分工沿用资料/多媒体规格；本轮未重写存储/Provider适配器 |
| 防错误安装、并发覆盖和无恢复发布 | 实际源码/同Run制品摘要不符、身份/发布锁冲突、恢复条件不足，不安装；不拿历史Run冒充 | 根AGENTS/Flow策略、现有安装器；保留完整性/锁/备份，600秒Run上限为用户明确要求而非推测性能阈值 |

其中业务封闭DTO仅保护已明确的动作/授权/签名语义，不能把本表当成“所有Object.keys长度限制都有必要”的豁免。语义版本检查不等于应用升级必须同版本；CAS revision不是程序版本。

## 4. 尚未逐项裁定的存量项（不能算必要性核验通过）

全代码模式检索会命中普通分支、样例、常量和调用，不等于存在同数量的独立校验。以下尚未完成所有调用者/规格/负例闭合，不能宣称本轮全项目清理完成：

- Chat上下文深度/键数/键长/队列和绑定数上限，与服务端生产者及实际资源约束的对应关系。
- 技能归档16MiB、展开64MiB、单entry与entry数上限：路径逃逸/解压炸弹防护必要，但每个数字的依据不能只写“沿用历史”。不得将尚未核实的阈值批量上调或归零。
- 图像/语音各Provider的输入数量、编码、输出字节限制，以及当前仍加载的旧通道中的重复校验。
- 管理列表/资金只读策略/外单/typed outcome中的其余精确字段集合：需要按读取展示与写入/签名语义分开，不机械全局替换`exact`。
- API各业务域注解长度、数据库schema/index自检与重复边界检查，以及地图/资源构建的校验：需分别核对实际存储/算法/授权依据；全局模式检索已覆盖，逐条裁定未完成。

这些是本次要求的未完成工作，不新增功能，也不是默认保留批准。所有必要性新增说明必须给具体风险和失败范围；不以新增日志、审批或统一“校验平台”代替删除重复代码。

## 5. 验收及证据边界

1. 正向：新增元数据不导致正常页面/任务不可用；输出不透传未知权限/endpoint，原业务字节/摘要不变。
2. 反向：缺字段、错类型、错主体、错摘要、部分ACK、UNKNOWN、重复扣费、越权等原负例仍拒绝。
3. 混合恢复：旧已完成、旧未决、新任务并存；异常局部隔离，不重放旧副作用。
4. 源码/测试/上线分别记录，修改旧测试时必须保留原受保护不变量，不删除失败样例来凑绿。

扫描证据：`/var/tmp/cyf-skill-adoption-20261024/validation-scan/inventory.json`（路径/行/模式/文件SHA）、`summary.json`、各定向测试日志。扫描基线API ff819208、Web8c84e51、Runtime b77f7d0；操作脚本来自共享根工作区实际字节，非干净HEAD快照。排除依赖、测试、产物和其他项目；扫描范围与“已逐条核实”分开记录。
