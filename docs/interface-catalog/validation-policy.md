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

## 4. 存量五类逐项裁定（2026-10-24续核）

本节覆盖本次库存检索命中的五类，不用“历史有测试”作为必要性理由。下表“删除”指候选源码；正式Flow、合并和安装状态另见handoff，不能把静态审计当作全业务线上验收。

| 类别 | 删除的非必要拒绝 | 保留的边界、依据及失败范围 | 实现/回归 |
| --- | --- | --- | --- |
| CHAT上下文 | 深度8、256字段/数组项、128字节键、256KiB快照、64KiB正文及派生envelope重复总量 | JSON类型/安全整数、循环对象、原contextHash和dispatch身份仍验证；完整事实不截断。数据嵌套迭代检查，不按嵌套层数判业务失败 | Runtime `chat-runtime.mjs`；`chat-runtime.test.mjs`含300字段/长键/40层/300KB中文及错摘要负例 |
| CHAT调度/持久记录 | 默认256排队、默认512绑定、1024条停止索引扫描；默认inbox1024/64MiB、dedupe100000/128MiB业务硬门槛；配置上限10GB/100000等无来源数值 | 并发lane仍串行；持久ACK、原指纹、归属/锁保留。已明确配置的正整数容量作为运维选择继续在ACK前背压，未配置不补猜测值；磁盘/IO真实失败不ACK。归档保留周期不是拒收门槛，本次不删除历史证据/改变retention | `chat-runtime`、`agent-client`；原显式maxFiles=1并发背压/去重/锁测试仍保留；新增默认300任务、514绑定恢复 |
| 技能ZIP | 压缩包默认16MiB、单entry默认16MiB、默认256entry（含API描述符残留）；API存储16MiB | 下载按批准byteLength/sha核对；Java byte[]/ByteBuffer int寻址必须可表示（不是业务容量）。解压仍只在安装暂存边界保留**总展开64MiB**防zip-bomb预算，禁止路径逃逸/链接/重复与别名冲突，并核对真实展开bytes=ZIP声明。总量约束已经覆盖单entry，故不另加16MiB。64MiB是现有解压安全预算，不声称是生产物理容量或Provider限制；不能推广到原始资料存储。显式安装预算不等于默认产品规则 | `SkillPackageStore`、`SkillPackageDescriptor`、`skill-install-manager`；API描述符按已验证展开长度读取并单独探测EOF，不对int上限+1；`SkillPackageDescriptorTest`覆盖小manifest/302条目和65MiB展开拒绝；`SkillPackageStoreTest`实读写16MiB+1；Runtime真实261entry安装、展开超预算、路径/摘要原负例 |
| 媒体通用层 | 三个通道instruction16000重复拒绝；资料快照/收件端16资料数限制；native MIME识别16MiB；图像返回16MiB；成果publish16MiB/data24MiB及下载/目录64MiB；语音结果/缓存/实时文本256KiB重复限制 | 身份/grant/原bytes/hash/长度、MIME真实解码和effect幂等仍保留；不能因预览不支持而禁止存储；下载仍核对声明长度和真实内容。MP4固定moov→trak→mdia→minf→stbl调用链不会任意递归，删除不可达depth8和传递参数；保留box范围/实际音频时长、采样计数和解码安全检查 | `conversation-*`、`controlled-image*`、`image-http-codec`、两artifact/deliverable Controller、`SpeechTranscriptionService`、`CliproxyRealtimeSessionClient`、`OpenAiCompatibleSpeechTranscriptionProvider`、`AudioDurationInspector`；native/imagegen/skill-runners、Controller/VoiceServices/Cliproxy/音频格式回归 |
| Provider专属 | 不把Provider限额重复套到通用资料快照；不增加格式存储白名单 | Images JSON编辑边界仍16张、单image_url≤20,971,520字符：官方API参考明确规定，2026-10-24核对 `https://developers.openai.com/api/reference/resources/images/methods/edit`。仅HTTP编辑适配器负责；当前image-edit技能manifest自身单输入合同不外推到其他技能。编码魔数/PNG/base64和真实错误仍检查。语音PCM256KiB是**分块大小**，不是拒绝超过该大小的总音频 | `skill-runners/image-provider`、`controlled-image-http-executor[-v3]`、`controlled-image-v3-files`、`CliproxyRealtimeSessionClient`；image与语音真实输入/关联测试 |
| Web只读 | 技能目录/变更列表/报价/费用嵌套字段精确数量、reasonCode封闭展示枚举；资金只读capability和接外单read/ACK多字段拒绝 | 只读投影忽略新增字段，报价金额/批准权限/expectedRevision保持原语义；写DTO仍封闭，未知动作/状态不能自动获得权限，新增reason只作展示。Money/projected package在比较和组装写请求前去掉扩展字段，避免新增metadata导致重复购买或错误CAS | `skillManagementContract`、`economyReadOnlyPreviewPolicy`、`useExternalServicePolicy`；`validation-compatibility`、`skill-market`、`juyiting-external-commission` |
| 成果Web残留 | `useHallArtifactTransfer`上传16MiB/下载64MiB及MIME白名单，`useOutputs`成果目录/下载64MiB、只读元数据精确字段拒绝 | 原型本已允许任意文件；同步取消传输面板accept与上限文案，缺MIME用通用二进制，畸形MIME/非安全整数/实际长度不符仍拒绝；回执与目录仅投影已知字段，未知URL/权限字段不消费。预览解码能力限制不扩成存储限制 | 原型规格2026-10-24传输节；`juyiting-artifact-transfer`新增65MiB真实流与长度不符负例、`juyiting-hall-deliverables`大成果与元数据投影、`output-preview-image-size` |
| 数据库启动 | transport、creation、requirement、provider-consent、settlement、bridge、followup、delivery-review、typed-review-context因额外**非唯一读索引**拒绝启动 | 必需索引仍验证字段/顺序/唯一性/前缀；额外UNIQUE会改变INSERT/去重事务语义仍拒绝。列类型/归属binary collation、CHECK表达式、触发器/外键和迁移基线检查保护持久状态和自动DDL，不因字段兼容移除；本次不执行生产DDL | 上述9个`*SchemaInitializer`；ProviderCostConsent/Followup/TypedReviewContext新增“额外读索引通过、额外唯一索引拒绝”；原DDL负例 |
| API业务字段/构建资产 | 本次源码库没有`@Size/@Length/@Min/@Max`注解门禁命中；不得照上轮泛称的“注解长度”虚构修改。删除sprite编码文件/PNG64MiB大小门槛 | `task_id`/idempotency100、tenant/client/owner50等身份长度对齐SQL列，Long/版本/金额防溢出；业务写DTO封闭保护动作、批准权限和摘要，不把它推广成只读展示要求。地图tile ID/尺寸/纹理引用和sprite解码总RGBA64MiB防解码放大保留，不以压缩文件字节拒绝。Gradle分层、锁定依赖/同RunSHA、制品恢复保护按根规则保留 | Controller/业务mapper及对应DDL；`validateLayering`；Web `validate-sprites`/mapValidation与资产回归。E14显式assets测试的测量/来源合同未借此删除，release测试仍按既有profile执行 |

边界说明：身份、事务、幂等、完整性、格式可解析性/实际能力及安装恢复属于必要事实；**固定数值不是仅靠写入此文档就成为物理事实**。保留的ZIP/RGBA解码预算是对不可信压缩内容的局部安全预算，而非平台资料限额/性能SLO；本次没有测量生产最大可承载文件、没有新增配额，也不声称“任意大小必成功”。真实资源错误仍如实失败。不能以本表概括性条目批准未来新增门槛。

## 5. 验收及证据边界

1. 正向：新增元数据不导致正常页面/任务不可用；输出不透传未知权限/endpoint，原业务字节/摘要不变。
2. 反向：缺字段、错类型、错主体、错摘要、部分ACK、UNKNOWN、重复扣费、越权等原负例仍拒绝。
3. 混合恢复：旧已完成、旧未决、新任务并存；异常局部隔离，不重放旧副作用。
4. 源码/测试/上线分别记录，修改旧测试时必须保留原受保护不变量，不删除失败样例来凑绿。

扫描证据：`/var/tmp/cyf-skill-adoption-20261024/validation-scan/inventory.json`（路径/行/模式/文件SHA）、`summary.json`、各定向测试日志。扫描基线API ff819208、Web8c84e51、Runtime b77f7d0；操作脚本来自共享根工作区实际字节，非干净HEAD快照。排除依赖、测试、产物和其他项目；扫描范围与“已逐条核实”分开记录。
