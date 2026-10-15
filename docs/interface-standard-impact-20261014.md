# 统一接口规范影响分析（2026-10-14）

**对应规范：[CYF统一接口规范1.2](interface-standard.md)。本次仅定规范及静态分析，未实施wire迁移。**

字段/类型/处理逻辑、配置和测试的具体落实见 [迁移详设](interface-standard-detailed-design.md)（2026-10-14追加，仍仅文档）。

## 1. 分析基线与证据边界

Owner：本聊天主Agent；归属现有任务 `JYT-SKILL-SE2-20261013` 的接口规范收口。

| 源码 | 本次核对基线 | 状态/边界 |
| --- | --- | --- |
| `/home/isp/wsps/cyf/api` | develop `7e2d07a05b9e9b18461e97abcff44642504320ff`；tree `0c28979ec1363f27b9d90b3b11b37456002250a5` | 本次文档修改前clean；本次只读远端develop回查相同 |
| `/home/isp/wsps/worktrees/skill-install-lifecycle-20261014` | `75a050c7d39b15dad22a9822256dcb9da6f3a7fe` | API尚未发布的修复候选；不能当develop或线上 |
| `/home/isp/wsps/cyf/web` | `a900ea4cff8158decc9b28888392c091fd0c62fe` | 本次远端develop回查相同；未改Web |
| `/home/isp/wsps/worktrees/skill-execution-20261014-runtime-111` | `b5126ea2a81a5c374f77c8f9c025b8796dd751ee` | 现用Runtime制品对应源码；远端develop回查仍 `b79558e4f18b431c1ff698432b3d6a7c07aac8d5`，两者不可混用 |

查询日期为2026-10-14；上述不是持续跟踪“最新”的承诺。本次不查询/变更生产服务，不将此前上线报告重新标为本次在线核验。覆盖公共响应、技能HTTP/WS、Runtime adapter/UDS、前端结果消费与相关文档，**不是全仓接口枚举完成**；第三方消费者和其他业务模块的逐端点清单仍需在各迁移批次补齐。

目标/非目标：确定全系统公共规范、明确已知差异和顺序；不重构全系统、不新增费用授权/外单、不启动构建、不改配置/数据库/服务。最早验证为Markdown链接、示例JSON解析及diff检查，功能测试未运行。

## 2. 已确认差异：不是“加一层data”就完成

| 对象/具体源码 | 当前事实 | 目标及影响 |
| --- | --- | --- |
| `common/jia-common-core/src/main/java/cn/jia/core/entity/{Result,JsonResult}.java` | code默认E0；响应含msg/status，data为null时可被省略；没有统一protocolVersion/requestId | 在原公共类型收敛信封，目标删除重复status、固定data与关联字段。影响所有使用该类型的Controller及消费者，**高影响**，不能全局先改后补客户端 |
| 同目录 `JsonResultPage.java`、`JsonRequestPage.java` | pageNum/total在响应根级，data为列表；请求存在extra_search等旧字段 | 分页元数据收进data业务对象，total用十进制字符串，命名统一；表格/列表/查询消费者同步。不直接改变既有默认容量 |
| `common/jia-common-service/src/main/java/cn/jia/core/resolver/GlobalExceptionResolver.java`；各局部ExceptionHandler | 现有全局和局部错误处理并存 | 保留原安全边界，统一code/msg/data/requestId和真实HTTP状态；不是简单叠加ResponseBodyAdvice；需防重复包装及泄密 |
| `chat/jia-chat-service/src/main/java/cn/jia/chat/api/{TaskSkillCapabilityController,ChatSkillResultController}.java` | 均返回JsonResult；前者显式设置错误body.status，后者失败分支未相同设置，可能与HTTP状态不同 | 删除重复HTTP状态来源后，以实际HTTP状态+稳定code为准。验证401/403/404/503和资源不可见语义，不能只测正常目录 |
| `agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentRuntimeV1Controller.java`；`agent/jia-agent-core/.../AgentRuntimeV1*` | 使用JsonResult；DTO/路径中installationId指Runtime安装，认证头与body做精确比对 | 公共信封、类型和明确runtimeInstallationId需连同HTTP客户端/安全适配同步；不能把改字段名等同取消认证比对 |
| `chat/jia-chat-service/src/main/java/cn/jia/chat/handler/AgentWebSocketHandler.java#sendEvent` | 复制业务字段到根级，再加type/channel/timestamp；不是data包裹 | 改为单一messageType和公共信封，显式业务DTO进data；影响注册、状态、任务、回执等所有实际消费者，**不只图片技能** |
| 候选 `SkillInstallResultService.java#receipt`、`SkillNativeResultMySqlTest.java` | 当前回执是扁平业务字段；测试读取data的假设已修正并通过原生MySQL/WS测试 | 现行修复正确不意味着目标已落实。迁移时发送端/Runtime/test一起采用目标data，不能只再把测试改回读取data |
| Runtime `conf/cyf-agent-runtime-v1/lib/execution-adapter.mjs` | 技能产品installationId与Runtime安装身份需分支区分；保留原执行runtimeInstanceId，同时核当前会话 | 拆清skillInstallationId/runtimeInstallationId和executionRuntimeInstanceId；通用传输适配不得覆盖业务事实。要测重连、跨安装、旧fence拒绝 |
| Runtime `conf/cyf-agent-runtime-v1/lib/runtime-client.mjs`；`conf/codex-ws-agent/skill-install-manager.mjs` | HTTP会话/ACK与技能命令、下载、durable结果分别处理 | 同步公共解析器、ID语义和错误分类；认证/隔离/安装事务不得随格式改动被削弱。durable旧消息不能直接重封装重算hash |
| API `agent/jia-agent-service/src/main/java/cn/jia/agent/hosting/UnixManagedHostingProvisioner.java`；Runtime `lib/{hosting-wire,hosting-server,hosting-control}.mjs` | UDS使用protocol/method及扁平字段；能力探测有精确字段集合 | 双端同时换公共信封及capabilities数据；保留私有socket权限、可信主体和generation验证。已有精确集合将拒绝新格式，不能只改一端 |
| Web `src/composables/juyiting/useHallSkillResults.js` | 可见 `response.data` / `response.data.data` / 原对象候选解析 | 明确区分HTTP库的response.data和wire.data，只在边界固定解包一次；移除业务层猜测式兜底 |
| Web `src/composables/juyiting/{hallSkillCapabilities,hallSkillResults}.js` | 对业务对象字段集合/字符串代际进行严格校验 | 保留业务校验；接入统一解包后的DTO，新增公共信封错误负例，不能为包裹变更放松字段校验 |
| Web `src/stores/api.js` | OAuth响应有外部固定字段；身份清理与鉴权失败处理相连 | OAuth属于明确外部协议映射，不强改成自有信封；自有API错误接入统一分类，不破坏登录/退出和身份切换取消 |

以上相对源码路径的根以对应基线仓库为准。`lib/...` 指Runtime的 `conf/cyf-agent-runtime-v1/lib/`。本表是确认过的入口，不代表只有这些文件需要改。

## 3. 影响范围分层

### A. 当前图片技能必须覆盖的链路（高优先级）

浏览器资料上传/选择 → 任务/会话权限 → 技能目录 → 命令与原生ACK → Runtime安装/执行 → 结果/回执 → 成果目录 → 固定版本下载。

- 公共结构、关联ID、不同安装ID、session/fence、原始执行实例、错误及状态、文件版本和摘要必须一致。
- 用户取消同Owner资格限制不影响认证、任务授权和资料隔离；平台费用只登记，不把这次规范变成费用控制改造。
- 商品购买/安装、资料访问和技能调用不是同一权限；既有订单/结算不能因统一名称而重置。
- 文件下载是二进制映射，不能为了统一响应破坏Content-Type、字节、hash、ACL或浏览器下载。

### B. 公共框架和其他业务（范围大，逐链路实施）

`JsonResult`/分页/全局异常一旦更改，影响agent、chat、economy及user/其他采用公共响应的业务和其前端/外部客户端。此次未逐个Controller和第三方客户端枚举，不能承诺“只改三个模块”。

- 必须先按公共类型与序列化入口找出消费者，再按业务链固定发布范围；不允许先发布全局包裹再让客户端宽松适配。
- OAuth、Provider SDK、文件/媒体流、第三方回调、健康探针分别按规范中的有限映射核对，不能直接套一个全局JSON过滤器。
- 更新AGENTS只能约束后续工作，不能自动改变正在运行的Agent或独立仓库。根AGENTS已加跨仓传达要求；Runtime Owner仍须显式接收固定规范。

### C. 数据、配置和运维（条件性高风险）

- **仅格式统一不必改业务表。** DTO到原实体映射可保留数据库列名；公共类型迁移本身不授权DDL。
- **持久wire受影响。** outbox/inbox、命令fingerprint、安装结果回执、执行快照及摘要是不可变事实。字段重命名、数值类型或包裹层变化可能改变canonical hash，不能原地覆盖后声称是同一消息。
- 用户已授权停机并忽略旧在途：不等待原安装/执行完成、不实现旧任务迁移兼容。保留历史数据，隔离旧执行输入，防止新启动自动重放；不重下单、不伪造ACTIVE。
- 不需要新增Provider配置、费用开关、服务、队列或每技能协议配置。HTTP头若新增requestId需核CORS/proxy透传；这是待验证影响，不代表本次修改生产CORS。
- 安全签名、历史证据和typedInspection不因规范冻结重签/重标；如新序列化改变验证材料，应单列适配与验证，不声称历史证据能直接复用。

## 4. 测试影响及当前失败的关系

1. 公共信封正反例：成功/错误/空data、真实HTTP状态、错误包装层、缺字段/未知字段/重复key、协议版本、整数/字符串边界。
2. 真实serializer与真实parser共用同一完整fixture：HTTP、WS结果/回执、UDS请求/响应都覆盖，不能各端mock出自己想要的格式。
3. 状态与安全：身份/资料越权、不同安装ID、授权代际变化、原始执行实例保留、同key异参、重投、先提交后回执、断线结果未知、下载字节一致。
4. 后续正式批继续保留真实事务、安装、认证、依赖完整性和分层检查；前端仍Flow，后端仍受控本地构建。文档检查不是这些测试的替代。

当前r18记录：原生技能MySQL→实际WS→ACTIVE/回执测试通过；完整批实际363项、1失败、0错误/跳过。失败在 `UnixManagedHostingProvisionerTest.actualJavaAdapterDrivesRuntimeInitialReplayAndFreeNewSessionWithoutCredentialWire` 的能力探测。**尚未证明它是协议格式问题**；后续私有Node UDS可返回available=true不能代替失败Java路径。不能声称制定规范即可解决该失败。

原始证据：`/var/tmp/cyf-skill-base-api-release-20261014-r18-receipt-full/failure-summary.json` 及对应build XML/log。接续仍需先捕获原Java transport异常、运行最小定向验证，不因本规范重新开始盲目完整构建。

## 5. 当前实施顺序（用户已确认一次性改造）

1. 实现前闭合Web/API/Runtime和仓内脚本的调用关系、字段映射及特殊出口，复用已有详设，不重新开展全接口文档工程。
2. 同一整体改造中修改公共边界及所有受影响消费者；尽早运行真实serializer→parser定向验证，不把公共类型单独发布。
3. 全量静态检查，按协议/行为抽样及关键安全回归；原Java UDS失败应在开发早期定位，不拖到图片最终验收。
4. 固定同批源码/制品，按现有前端Flow、后端本地规则测试构建；协调停机、隔离旧执行输入、安装全部组件，不等待旧在途或维持旧wire。
5. 以新任务执行真实上传→识别→修改→下载和在线协议验证，结果通过后开放入口。旧任务不作验收条件，新任务的权限、事务和幂等不豁免。

以上替代原先分批收敛安排；文档确定不代表实施或发布已完成。

## 6. 文档与历史引用收口

- `api/AGENTS.md` 是强制阅读入口，本文所链接的公共规范是唯一公共定义；项目根AGENTS将规则传达到Web及参与本项目的Runtime任务。
- `specs/juyiting-skill-execution/design-se2.md` 继续承担技能业务详设，不再另行决定公共信封。
- `specs/agent-economy-marketplace/v0-api-contract.md` 中旧API-key安装说明与新版原生认证不能并列作为当前实现指令；迁移/发布批须给出准确替代指向，历史字节保留供追溯。
- `docs/codex-ws-agent.md` 与 `docs/implementation/GSS-HOSTING-CONTROL-CONTRACT-20261010.md` 的现行wire说明仍是旧版实测/修复来源，不冒充已实现目标；后续随实际切换更新稳定知识和源码版本。
- 不复制另一份完整规范到Web/Runtime仓库，不新增独立审批/Reviewer/规范服务。跨仓用固定规范版本和fixture摘要，交付记录分别写文档完成、开发完成、发布和验收状态。

## 7. 本次实际交付

本次新增两份规范/分析文档、修改api/root AGENTS入口并在现有handoff记录；未改业务源码、schema、依赖、技能包、配置或服务，未运行Gradle/Flow、未发布。该完成状态只针对“规范确定并分析影响范围”，不表示图片能力或统一协议迁移已验收。

## 8. 二次全量声明扫描与漏项复查（2026-10-14追加）

新增[逐接口目录](interface-catalog/README.md)、[关键链字段契约](interface-catalog/critical-contracts.md)、[风险评估](interface-catalog/risk-review-20261014.md)。固定API基线扫描出99个Controller、596个显式method/path条目（含20个未限制method的ANY声明），不是线上路由数量。新增范围含裸DTO、个人资料/关联、Native会话执行effect/result/resume、第三方模块自有管理接口、3条WS及多个SSE；一次性凭据重放、202、货币单位等设计修正已回写规范1.1。完整业务schema/消费者枚举仍有明确缺口，不把索引当迁移完成。


## 2026-10-15 范围纠偏（当前口径）

此前全业务字段/权限/支付事务核查和全量语法候选分类，不再作为本次接口迁移前置条件。当前有限实施矩阵及公共边界裁决见`interface-standard-detailed-design.md`末节（目录文件从上级查阅）。第三方回调保持原协议；历史业务缺陷单列，不顺手修复。596是源码库存总数，不是剩余未核对或需改代码数量。审计JSON保留历史remaining并记录currentMigrationScope；未据此虚标全系统设计/开发/验收完成。本轮仅文档及本地源码回读，不构建、不部署、不访问生产数据。
