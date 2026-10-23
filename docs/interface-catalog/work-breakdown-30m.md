# 接口规范补齐：≤30分钟工作包

> 执行策略已被用户后续要求取代：统一分析、一次性修改、集中抽样验证，不按本清单逐模块交付。本文仅保留历史文档工作量拆分，不作为当前实施工期。

日期：2026-10-14。任务沿用`JYT-SKILL-SE2-20261013`，Owner仍为本聊天主Agent；不派新Owner、不创建Reviewer、不启动子Agent。

## 1. 范围和时间约定

- 本轮拆的是**接口规范/字段详设的剩余工作**：69条已有设计的缺口、527条仅索引接口，以及消息/特殊出口/消费者及风险收口。不重新安排“制定方案”。
- 不包含业务代码迁移、缺陷修复、机器schema的全量实现、功能测试、构建、发布或生产在途处置。以后实施需按实际冻结接口包另拆，不能拿本文当总系统改造工时。
- 每包**20–25分钟分析/写入 + 5分钟自检，单次工作预算≤30分钟**。这是用户指定的工作粒度，不是已实测的完成耗时或交付保证。
- 25分钟时若还有复杂联合、跨服务权限或未知消费者，将剩余内容按“请求字段/响应变体/权限状态”拆成子包；父包保持未完成，不硬填推测、不跳校验。本文包数是当前分组，必要拆分后会增加。
- 常规接口每包最多4条，必须同Controller；二进制/流/multipart/raw-body/不限定method/交易执行类逐条拆。Object返回仍须向下读真实body；发现复杂即拆，不能凭Object名当通用对象完成。
- 共享DTO/权限/错误定义只写一次，其他包引用；每包不得重复设计公共信封。既有规范1.1、字段详设和风险表是输入，不再重做它们。
- 所有包当前均**未执行**；此文件只是拆分，不改变库存的核定状态。

## 2. 依赖与优先顺序

1. **先K01—K25**：补齐当前图片/技能关键链，先字段，再该接口权限/状态/错误和消费者引用。
2. **再N01—N11及相关B包**：闭合关键链消息/特殊出口；B中agent/chat/economy优先，其余按模块。
3. F01—F06随相应包自然收口，不必最后重读一次全仓；F07—F08最终查漏。
4. 不因此自动启动全系统迁移，也不阻塞按原精确wire修复当前图片交付。共享规范变化仍不能单端发布。

## 3. 每包的固定交付/验收

写回现有`critical-contracts.md`或对应`http-<模块>.md`的指定操作段：
- method/path、启用条件、path/query/header/body的字段类型、必填/可null、嵌套变体、范围/枚举；当前→目标字段映射。
- 成功/失败状态和code、身份/资源ACL、原状态机、副作用/事务/幂等及恢复规则。
- producer/consumer源码文件/符号及固定基线，DTO或共享schema引用，最早真实serializer→parser验证位置与关键负例。
- 无证据的字段/权限/消费者明确记录为缺口，包不标“契约定义完成”。不得将列出测试用例当测试已执行。
- 自检仅文档/JSON/路径/源码事实核对；纯文档不跑Gradle/Flow。没有业务实现时不得标开发/上线完成。

所有API路径根为`/home/isp/wsps/cyf/api`；基线`7e2d07a05b9e9b18461e97abcff44642504320ff`，完整行号/文件摘要见[库存](http-source-inventory.json)。Web/Runtime基线和工作区沿用[详设](../interface-standard-detailed-design.md)。

## 4. 关键链缺口：25包

每行均为一个≤30分钟预算包；编号引用[关键契约](critical-contracts.md)，不是重新从零定义接口。

| 包 | 范围 | 指定接口 | 本包补齐内容 |
| --- | --- | --- | --- |
| K01 | Runtime安装创建/查询/撤销 | RT01—RT03 | 安装状态枚举、管理权限、撤销影响和创建唯一键 |
| K02 | Runtime注册/会话/心跳 | RT04—RT06 | 秘密返回边界、会话代际、真实重试语义，不缓存秘密 |
| K03 | Runtime命令ACK | RT07 | ACK状态表、deliveryVersion、path/body/会话比对及幂等 |
| K04 | 个人文件列表/上传/详情/改名 | PW01—PW04 | FileDetail嵌套字段、上传operation、202/201及ETag |
| K05 | 文件版本/预览/下载 | PW05—PW09 | Version/Preview联合、固定版本、byteLength、Map.of(null)修复落点 |
| K06 | 文件使用/回收/恢复/操作查询 | PW10—PW13 | Usage.activeExecutions、impactRevision、412/428和原操作恢复 |
| K07 | 任务资料关联 | LK01—LK03 | 版本/角色/资料ACL、关系revision和删除后快照 |
| K08 | 会话资料关联 | LK04—LK06 | 会话scope、ETag/幂等及关联解除影响 |
| K09 | 技能能力与费用登记 | IM01 | catalogRevision、CostInfo.rate、updatedAt及不可调用原因，不增费用准入 |
| K10 | 技能商城目录与能力 | SK01—SK03 | 商品及scope摘要、公开字段/管理字段的边界 |
| K11 | 技能报价/购买/订单/权益 | SK04—SK07 | SILVER单位、订单状态/权限、幂等与安装身份映射 |
| K12 | 一次性安装凭据 | SK08 | 字段/秘密分类、409不可重放、原托管及pending保护 |
| K13 | 技能包发布与下载 | SK09—SK10 | multipart元数据/zip摘要、管理权限与原安装下载授权 |
| K14 | 议事请求与受理回执 | IM02 | Discussion/Selector字段闭合、新schema、请求幂等与业务requestId |
| K15 | TypedOutcome多态结果 | IM03 | ANSWER/CLARIFICATION/ACTION及actionProgress缺口；按变体分表，签名事实不改 |
| K16 | 技能结果读取 | IM04 | SkillResult字段与状态组合、请求/执行绑定、resultSha256投影 |
| K17 | 成果目录/下载/HEAD | IM05—IM07 | Output及替换关系、206/416/HEAD、字节与权限 |
| K18 | Runtime资料输入下载 | IM08 | manifest证明、资料引用、读取后fence与错误码 |
| K19 | 普通Runtime队列/启动/输入目录 | RF01—RF03 | 命令/输入精确字段、原队列范围和启动状态 |
| K20 | 普通Runtime下载/暂存/提交/失败 | RF04—RF07 | Commit/ExecutionView嵌套、hash/长度、事务与失败状态 |
| K21 | 会话Runtime命令与租约 | CR01—CR04 | Command变体、旧只读队列与新契约、lease/session/execution三种身份 |
| K22 | 会话Runtime输入/字节/暂存/提交 | CR05—CR08 | source联合及Fence/表单字段，不混用普通Runtime通道 |
| K23 | 技能外部副作用开始/结果 | CR09—CR10 | effect状态、snapshot/providerBinding、结果未知不得重开Provider |
| K24 | 技能恢复/结果提交/失败 | CR11—CR13 | ResumeReceipt/manifest投影、inputReceipts、allowedWork及结果幂等 |
| K25 | 已退役写入口 | §7的5条POST | 410、认证/参数绑定优先级；不复活旧inspection/consent写接口 |

## 5. 消息/流协议：11包

| 包 | 范围 | 本包交付 |
| --- | --- | --- |
| N01 | UDS capabilities/prepare | scope/关联字段/manifest安全边界、精确成功与错误对象 |
| N02 | UDS ensure/observe | UNKNOWN/RECOVERY_REQUIRED/SERVICE_READY联合和当前在线证明 |
| N03 | Agent WS握手/注册/状态 | 逐messageType及连接身份、版本拒绝、关联ID |
| N04 | Agent WS命令/任务消息 | dispatch/task事件字段、fence和原消息幂等 |
| N05 | Agent WS结果/安装回执 | result/receipt字段、correlation、提交后发送和pending清理 |
| N06 | Agent WS聊天与分片 | start/delta/end/error字段和顺序、终态定义 |
| N07 | 普通/ws/chat | 裸文本及EOM替换契约、会话授权和实际消费者 |
| N08 | sms /websocket | 消息种类/登录凭据位置、scope及单连接限制 |
| N09 | Task SSE | 事件字段、scope内游标/Last-Event-ID和重放 |
| N10 | Chat stream/conversation SSE | 开始/片段/终态/错误、取消/断流、两个入口差异 |
| N11 | Scene/Archive SSE | 两条流的schema引用、游标及resync；共享部分只引用不重抄 |

## 6. 527条仅索引接口：逐包清单

以下是不同于K包的接口，不重复统计69条已有设计。专项一次一条，普通一次最多四条；每包补齐第3节内容，不能以“同公共规范”代替业务字段。

| 模块 | 接口数 | 工作包数 |
| --- | ---: | ---: |
| agent | 123 | 67 |
| base | 7 | 2 |
| chat | 57 | 33 |
| dwz | 7 | 2 |
| economy | 4 | 4 |
| isp | 61 | 18 |
| kefu | 11 | 5 |
| material | 39 | 13 |
| oauth | 13 | 6 |
| point | 14 | 5 |
| sms | 17 | 5 |
| task | 12 | 5 |
| user | 80 | 26 |
| workflow | 25 | 10 |
| wx | 57 | 40 |

### agent

#### B001 · AgentBountySettlementController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/tasks/{taskId}/funding/complete` → `complete`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/funding/AgentBountySettlementController.java:32`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B002 · AgentBountySettlementController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/tasks/{taskId}/settlement` → `read`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/funding/AgentBountySettlementController.java:25`。

#### B003 · AgentCollaborationRequestController · 普通 · 3条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/hall/collaboration/requests` → `submit`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentCollaborationRequestController.java:32`。
- `GET /agent/hall/collaboration/requests/{requestId}` → `get`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentCollaborationRequestController.java:51`。
- `GET /agent/hall/collaboration/requests/request` → `request`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentCollaborationRequestController.java:58`。

#### B004 · AgentCommandOperationsCapabilitiesController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/internal/command-operations/capabilities` → `capabilities`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentCommandOperationsCapabilitiesController.java:34`。

#### B005 · AgentCommandOperationsController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/internal/command-operations/metrics` → `metrics`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentCommandOperationsController.java:74`。
- `GET /agent/internal/command-operations/dlq` → `dlq`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentCommandOperationsController.java:80`。
- `GET /agent/internal/command-operations/audit` → `audit`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentCommandOperationsController.java:90`。
- `GET /agent/internal/command-operations/v1/operations/{operationId}` → `operationV1`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentCommandOperationsController.java:100`。

#### B006 · AgentCommandOperationsController · 普通 · 3条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/internal/command-operations/v1/dlq/{deliveryId}/redrive` → `redriveV1`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentCommandOperationsController.java:110`。
- `POST /agent/internal/command-operations/dlq/{deliveryId}/redrive` → `redrive`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentCommandOperationsController.java:124`。
- `POST /agent/internal/command-operations/deliveries/{deliveryId}/reissue` → `reissue`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentCommandOperationsController.java:136`。

#### B007 · AgentController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/tasks/{taskId}/funding/cancel` → `cancelTaskFunding`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:368`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B008 · AgentController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/tasks/{taskId}/execution-authorizations/{grantId}/revoke` → `revokeTaskExecutionGrant`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:416`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B009 · AgentController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/register` → `register`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:152`。
- `GET /agent/list` → `list`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:157`。
- `GET /agent/map` → `mapAgents`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:165`。
- `GET /agent/capabilities` → `capabilities`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:170`。

#### B010 · AgentController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/personas/catalog` → `personaCatalog`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:176`。
- `POST /agent/personas/{personaCode}/bind` → `bindPersonaHttp`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:185`。
- `POST /agent/personas/bindings/{bindingId}/repair` → `repairPersonaBinding`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:238`。
- `DELETE /agent/personas/{personaCode}/bind` → `unbindPersona`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:244`。

#### B011 · AgentController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/roster` → `roster`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:289`。
- `GET /agent/{agentId}` → `get`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:299`。
- `PUT /agent/{agentId}/status` → `updateStatus`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:310`。
- `GET /agent/{agentId}/tasks` → `getTasks`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:315`。

#### B012 · AgentController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/personas` → `personas`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:320`。
- `GET /agent/persona/{name}` → `persona`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:325`。
- `POST /agent/dialogue` → `dialogue`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:330`。
- `GET /agent/stats` → `stats`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:335`。

#### B013 · AgentController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/tasks/search` → `searchTasks`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:340`。
- `POST /agent/tasks/status-counts` → `countTasksByStatus`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:345`。
- `POST /agent/tasks` → `createTask`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:350`。
- `GET /agent/tasks/{taskId}` → `getTask`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:363`。

#### B014 · AgentController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/tasks/{taskId}/quotes` → `quoteTask`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:381`。
- `POST /agent/tasks/{taskId}/claim` → `claimTask`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:392`。
- `POST /agent/tasks/{taskId}/assign` → `assignTask`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:403`。
- `POST /agent/tasks/{taskId}/recommend` → `recommendTaskAssignees`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:431`。

#### B015 · AgentController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/tasks/{taskId}/team-recommendation` → `recommendTaskTeam`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:437`。
- `POST /agent/tasks/{taskId}/auto-assign` → `autoAssignTask`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:450`。
- `POST /agent/tasks/{taskId}/report` → `reportTask`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:455`。
- `POST /agent/tasks/{taskId}/notes` → `addTaskNote`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:460`。

#### B016 · AgentController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/tasks/{taskId}/notes` → `listTaskNotes`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:465`。
- `POST /agent/tasks/{taskId}/archive` → `archiveTask`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:470`。
- `POST /agent/evaluate` → `evaluate`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:475`。
- `POST /agent/compare` → `compare`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:480`。

#### B017 · AgentController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/evaluation/{agentName}` → `evaluationHistory`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:485`。
- `GET /agent/evaluation/latest/{agentName}` → `latestEvaluation`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:490`。
- `GET /agent/evaluation/stats` → `evaluationStats`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:495`。
- `DELETE /agent/evaluation/{id}` → `deleteEvaluation`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentController.java:500`。

#### B018 · AgentHostingRentController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/{agentId}/hosting-lease` → `lease`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentHostingRentController.java:79`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B019 · AgentHostingRentController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/hosting-leases/{leaseId}/renewal-quotes` → `renewalQuote`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentHostingRentController.java:85`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B020 · AgentHostingRentController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/hosting-leases/{leaseId}/renewals` → `renew`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentHostingRentController.java:93`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B021 · AgentHostingRentController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/personas/{personaCode}/hosting-rent/quotes` → `quote`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentHostingRentController.java:61`。

#### B022 · AgentSceneController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/scenes/{sceneId}/events` → `events`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentSceneController.java:58`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B023 · AgentSceneController · 普通 · 2条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/scenes/{sceneId}/snapshot` → `snapshot`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentSceneController.java:48`。
- `POST /agent/scenes/{sceneId}/phases` → `phase`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentSceneController.java:80`。

#### B024 · AgentTaskArtifactController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/tasks/{taskId}/artifacts/{artifactId}/versions/{artifactVersion}/content` → `download`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskArtifactController.java:115`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B025 · AgentTaskArtifactController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/tasks/{taskId}/artifacts` → `publish`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskArtifactController.java:72`。

#### B026 · AgentTaskArtifactOutcomeController · 普通 · 2条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/tasks/{taskId}/artifact-outcomes/accept` → `accept`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskArtifactOutcomeController.java:87`。
- `GET /agent/tasks/{taskId}/artifact-outcomes/accepted` → `accepted`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskArtifactOutcomeController.java:111`。

#### B027 · AgentTaskCancellationController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/tasks/{taskId}/cancel` → `cancel`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskCancellationController.java:33`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B028 · AgentTaskContextPackController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/tasks/{taskId}/context-pack` → `contextPack`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskContextPackController.java:49`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B029 · AgentTaskCreationOperationController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/tasks/creation-operations` → `create`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskCreationOperationController.java:60`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B030 · AgentTaskCreationOperationController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/tasks/creation-operations/request` → `request`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskCreationOperationController.java:80`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B031 · AgentTaskCreationOperationController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/tasks/creation-operations/v2` → `createV2`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskCreationOperationController.java:91`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B032 · AgentTaskCreationOperationController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/tasks/creation-operations/v2/request` → `requestV2`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskCreationOperationController.java:107`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B033 · AgentTaskDeliberationOperationController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/tasks/{taskId}/point-and-deliberate` → `point`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskDeliberationOperationController.java:76`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B034 · AgentTaskDeliberationOperationController · 普通 · 2条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/tasks/{taskId}/assignment-operation` → `read`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskDeliberationOperationController.java:61`。
- `GET /agent/tasks/{taskId}/point-and-deliberate/request` → `pointRequest`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskDeliberationOperationController.java:92`。

#### B035 · AgentTaskDeliverableController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/tasks/{taskId}/deliverables/{artifactId}/versions/{artifactVersion}/content` → `download`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskDeliverableController.java:82`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B036 · AgentTaskDeliverableController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/tasks/{taskId}/deliverables/{artifactId}/versions/{artifactVersion}` → `previewContent`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskDeliverableController.java:127`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B037 · AgentTaskDeliverableController · 普通 · 2条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/tasks/{taskId}/deliverables` → `list`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskDeliverableController.java:65`。
- `GET /agent/tasks/{taskId}/deliverables/{artifactId}/versions/{artifactVersion}/preview` → `preview`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskDeliverableController.java:103`。

#### B038 · AgentTaskEventStreamController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/tasks/{taskId}/events` → `events`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskEventStreamController.java:63`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B039 · AgentTaskFormalDeliveryController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/tasks/{taskId}/formal-deliveries/{deliveryId}/rework-executions` → `createReworkExecution`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskFormalDeliveryController.java:147`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B040 · AgentTaskFormalDeliveryController · 普通 · 3条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/tasks/{taskId}/formal-deliveries` → `list`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskFormalDeliveryController.java:93`。
- `POST /agent/tasks/{taskId}/formal-deliveries` → `submit`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskFormalDeliveryController.java:114`。
- `POST /agent/tasks/{taskId}/formal-deliveries/{deliveryId}/decision` → `decide`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskFormalDeliveryController.java:130`。

#### B041 · AgentTaskProviderCostConsentController · 普通 · 2条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/tasks/{taskId}/point-and-start-cost-consents/{consentId}` → `get`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskProviderCostConsentController.java:80`。
- `GET /agent/tasks/{taskId}/point-and-start-cost-consents/request` → `request`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskProviderCostConsentController.java:89`。

#### B042 · AgentTaskRequirementSnapshotController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/tasks/{taskId}/requirements/current` → `current`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskRequirementSnapshotController.java:41`。

#### B043 · AgentTaskWorkspaceController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/tasks/{taskId}/workspace` → `workspace`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskWorkspaceController.java:40`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B044 · AgentWorkItemPlanController · 普通 · 2条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/tasks/{taskId}/work-item-plans/suggest` → `suggest`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentWorkItemPlanController.java:56`。
- `POST /agent/tasks/{taskId}/work-item-plans/confirm` → `confirm`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentWorkItemPlanController.java:72`。

#### B045 · AgentWorkItemReassignmentController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/tasks/{taskId}/work-items/{workItemId}/reassignments/{reassignmentId}/lease` → `readLease`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentWorkItemReassignmentController.java:81`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B046 · AgentWorkItemReassignmentController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/tasks/{taskId}/work-items/{workItemId}/reassignments/{reassignmentId}/lease/start` → `startLease`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentWorkItemReassignmentController.java:99`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B047 · AgentWorkItemReassignmentController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/tasks/{taskId}/work-items/{workItemId}/reassignments/{reassignmentId}/lease/heartbeat` → `heartbeatLease`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentWorkItemReassignmentController.java:117`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B048 · AgentWorkItemReassignmentController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/tasks/{taskId}/work-items/{workItemId}/reassignments` → `reassign`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentWorkItemReassignmentController.java:63`。

#### B049 · AgentWorkItemRuntimeResultController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /internal/agent/tasks/{taskId}/work-items/{workItemId}/reassignments/{reassignmentId}/commands/{commandId}/lease` → `readLease`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentWorkItemRuntimeResultController.java:82`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B050 · AgentWorkItemRuntimeResultController · 普通 · 2条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /internal/agent/tasks/{taskId}/work-items/{workItemId}/reassignments/{reassignmentId}/commands/{commandId}/result-commit` → `commit`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentWorkItemRuntimeResultController.java:52`。
- `GET /internal/agent/tasks/{taskId}/work-items/{workItemId}/reassignments/{reassignmentId}/commands/{commandId}/result-commit` → `readResult`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentWorkItemRuntimeResultController.java:71`。

#### B051 · ControlledImagePointAndStartController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/tasks/{taskId}/point-and-start-controlled-image/request` → `get`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/ControlledImagePointAndStartController.java:69`。

#### B052 · ConversationDeliverableController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/conversations/{conversationId}/deliverables` → `list`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/conversation/ConversationDeliverableController.java:37`。

#### B053 · EconomyReadOnlyPreviewController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /economy/preview/agents/{agentId}/hosting-lease` → `hostingLease`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/EconomyReadOnlyPreviewController.java:112`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B054 · EconomyReadOnlyPreviewController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /economy/preview/capabilities` → `capabilities`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/EconomyReadOnlyPreviewController.java:54`。
- `GET /economy/preview/wallet` → `wallet`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/EconomyReadOnlyPreviewController.java:62`。
- `GET /economy/preview/ledger` → `ledger`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/EconomyReadOnlyPreviewController.java:70`。
- `GET /economy/preview/skill-products` → `products`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/EconomyReadOnlyPreviewController.java:78`。

#### B055 · EconomyReadOnlyPreviewController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /economy/preview/skill-products/{productId}` → `product`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/EconomyReadOnlyPreviewController.java:86`。
- `GET /economy/preview/agents/{agentId}/skills` → `agentSkills`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/EconomyReadOnlyPreviewController.java:95`。
- `GET /economy/preview/hosting-plan` → `hostingPlan`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/EconomyReadOnlyPreviewController.java:104`。
- `POST /economy/preview/bounty-estimates` → `bountyEstimate`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/EconomyReadOnlyPreviewController.java:121`。

#### B056 · HallPrivateMarkController · 普通 · 2条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/hall/items/{sourceType}/{sourceId}/mark` → `get`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/HallPrivateMarkController.java:19`。
- `PATCH /agent/hall/items/{sourceType}/{sourceId}/mark` → `mark`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/HallPrivateMarkController.java:25`。

#### B057 · HallReadController · 普通 · 2条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/hall/overview` → `overview`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/HallReadController.java:24`。
- `GET /agent/hall/items` → `items`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/HallReadController.java:32`。

#### B058 · HallRequestDraftController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/hall/drafts` → `create`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/HallRequestDraftController.java:54`。
- `GET /agent/hall/drafts/{draftId}` → `get`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/HallRequestDraftController.java:67`。
- `GET /agent/hall/drafts` → `list`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/HallRequestDraftController.java:74`。
- `PUT /agent/hall/drafts/{draftId}` → `replace`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/HallRequestDraftController.java:82`。

#### B059 · HallRequestDraftController · 普通 · 2条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/hall/drafts/{draftId}/discard` → `discard`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/HallRequestDraftController.java:92`。
- `POST /agent/hall/drafts/{draftId}/submit` → `submit`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/HallRequestDraftController.java:104`。

#### B060 · HallSubmissionController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/hall/executions/{executionId}/results` → `executionResults`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/HallSubmissionController.java:46`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B061 · HallSubmissionController · 普通 · 2条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/hall/submissions/request` → `submission`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/HallSubmissionController.java:29`。
- `GET /agent/hall/cases/{caseId}` → `caseView`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/HallSubmissionController.java:38`。

#### B062 · PersonalWorkspaceExecutionController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/personal-workspace/executions/capabilities` → `capabilities`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceExecutionController.java:36`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B063 · PersonalWorkspaceExecutionController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/personal-workspace/executions` → `create`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceExecutionController.java:42`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B064 · PersonalWorkspaceExecutionController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/personal-workspace/executions` → `list`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceExecutionController.java:56`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B065 · PersonalWorkspaceExecutionController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/personal-workspace/executions/request` → `request`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceExecutionController.java:69`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B066 · PersonalWorkspaceExecutionController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `GET /agent/personal-workspace/executions/{executionId}` → `get`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceExecutionController.java:77`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B067 · PersonalWorkspaceExecutionController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-agent.md`相应操作段。
- `POST /agent/personal-workspace/executions/{executionId}/revoke-inputs` → `revoke`；源码`agent/jia-agent-service/src/main/java/cn/jia/agent/api/PersonalWorkspaceExecutionController.java:83`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

### base

#### B068 · DictController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-base.md`相应操作段。
- `GET /dict/get` → `find`；源码`base/jia-base-service/src/main/java/cn/jia/base/api/DictController.java:32`。
- `POST /dict/create` → `create`；源码`base/jia-base-service/src/main/java/cn/jia/base/api/DictController.java:45`。
- `POST /dict/update` → `update`；源码`base/jia-base-service/src/main/java/cn/jia/base/api/DictController.java:58`。
- `POST /dict/list` → `list`；源码`base/jia-base-service/src/main/java/cn/jia/base/api/DictController.java:71`。

#### B069 · DictController · 普通 · 3条

- 预算：≤30分钟；交付位置：`http-base.md`相应操作段。
- `DELETE /dict/delete` → `delete`；源码`base/jia-base-service/src/main/java/cn/jia/base/api/DictController.java:87`。
- `GET /dict/locale` → `locale`；源码`base/jia-base-service/src/main/java/cn/jia/base/api/DictController.java:99`。
- `GET /dict/cleanCache` → `cleanCache`；源码`base/jia-base-service/src/main/java/cn/jia/base/api/DictController.java:109`。

### chat

#### B070 · ActiveAgentController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `GET /agent/active` → `activeAgents`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ActiveAgentController.java:22`。

#### B071 · AgentInspectionInputController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `GET /internal/agent/chat/requests/{requestId}/turns/{turnId}/inspection/inputs/{sourceRefId}/content` → `content`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/AgentInspectionInputController.java:37`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B072 · AgentTaskControlledImageCapabilityController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `GET /agent/tasks/{taskId}/point-and-start-controlled-image-capability` → `read`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/AgentTaskControlledImageCapabilityController.java:36`。

#### B073 · AgentTaskPointAndStartCapabilityController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `GET /agent/tasks/{taskId}/point-and-start-capability` → `read`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/AgentTaskPointAndStartCapabilityController.java:42`。

#### B074 · AgentTaskSelectedOutputFinalizationController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `POST /agent/tasks/{taskId}/finalizations` → `submit`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/AgentTaskSelectedOutputFinalizationController.java:63`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B075 · AgentTaskSelectedOutputFinalizationController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `GET /agent/tasks/{taskId}/finalizations/request` → `byRequest`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/AgentTaskSelectedOutputFinalizationController.java:73`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B076 · AgentTaskSelectedOutputFinalizationController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `GET /agent/tasks/{taskId}/finalizations/{operationId}` → `byOperation`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/AgentTaskSelectedOutputFinalizationController.java:80`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B077 · AgentTaskThreadController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `POST /chat/task-threads/{taskId}/team` → `getOrCreateTeamThread`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/AgentTaskThreadController.java:28`。
- `GET /chat/task-threads/{taskId}/team` → `getTeamThread`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/AgentTaskThreadController.java:39`。
- `POST /chat/task-threads/{taskId}/team/messages` → `appendTeamMessage`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/AgentTaskThreadController.java:48`。
- `GET /chat/task-threads/{taskId}/team/messages` → `listTeamMessages`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/AgentTaskThreadController.java:57`。

#### B078 · ArchiveController · 普通 · 3条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `GET /archive/v1/catalog` → `catalog`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchiveController.java:34`。
- `GET /archive/v1/editions/{editionId}/preface` → `preface`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchiveController.java:50`。
- `GET /archive/v1/editions/{editionId}/chapters/{chapterId}` → `chapter`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchiveController.java:68`。

#### B079 · ArchivePersonalDataController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `GET /archive/v1/me/progress/{editionId}` → `progress`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchivePersonalDataController.java:46`。
- `PUT /archive/v1/me/progress/{editionId}` → `putProgress`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchivePersonalDataController.java:57`。
- `GET /archive/v1/me/bookmarks` → `bookmarks`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchivePersonalDataController.java:72`。
- `PUT /archive/v1/me/bookmarks/{bookmarkId}` → `putBookmark`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchivePersonalDataController.java:92`。

#### B080 · ArchivePersonalDataController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `DELETE /archive/v1/me/bookmarks/{bookmarkId}` → `deleteBookmark`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchivePersonalDataController.java:107`。
- `GET /archive/v1/me/notes` → `notes`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchivePersonalDataController.java:125`。
- `PUT /archive/v1/me/notes/{noteId}` → `putNote`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchivePersonalDataController.java:146`。
- `DELETE /archive/v1/me/notes/{noteId}` → `deleteNote`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchivePersonalDataController.java:161`。

#### B081 · ArchiveQuestionController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `GET /archive/v1/me/questions/{questionId}/events` → `events`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchiveQuestionController.java:78`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B082 · ArchiveQuestionController · 普通 · 3条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `PUT /archive/v1/me/questions/{questionId}` → `create`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchiveQuestionController.java:50`。
- `GET /archive/v1/me/questions/{questionId}` → `get`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchiveQuestionController.java:64`。
- `POST /archive/v1/me/questions/{questionId}/retry` → `retry`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/archive/http/ArchiveQuestionController.java:97`。

#### B083 · ChatBountyExecutionTerminationController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `POST /chat/conversations/{conversationId}/requests/{requestId}/abandon-execution` → `abandon`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatBountyExecutionTerminationController.java:47`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B084 · ChatBountyExecutionTerminationController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `GET /chat/conversations/{conversationId}/requests/{requestId}/abandon-execution` → `outcome`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatBountyExecutionTerminationController.java:55`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B085 · ChatBountyInteractionV3Controller · 普通 · 3条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `GET /chat/conversations/{conversationId}/interactions/context` → `context`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatBountyInteractionV3Controller.java:44`。
- `GET /chat/conversations/{conversationId}/interactions/provider-consents/request` → `getIssue`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatBountyInteractionV3Controller.java:64`。
- `GET /chat/conversations/{conversationId}/interactions/request` → `getInteraction`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatBountyInteractionV3Controller.java:79`。

#### B086 · ChatBountyRequestIndexController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `GET /chat/conversations/{conversationId}/requests` → `requests`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatBountyRequestIndexController.java:48`。

#### B087 · ChatController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `POST /chat/stream` → `handleChat`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:166`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B088 · ChatController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `POST /chat/turns/{turnId}/cancel` → `cancelChatTurn`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:538`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B089 · ChatController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `POST /chat/requests/{requestId}/cancel` → `cancelPendingChatTurns`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:548`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B090 · ChatController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `GET /chat/conversation/events` → `conversationEvents`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:662`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B091 · ChatController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `GET /chat/capabilities` → `chatCapabilities`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:503`。
- `GET /chat/requests/{requestId}` → `getChatRequest`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:522`。
- `GET /chat/turns/{turnId}` → `getChatTurn`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:530`。
- `POST /chat/stop_stream` → `stopStream`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:578`。

#### B092 · ChatController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `DELETE /chat/conversation/delete` → `deleteConversation`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:585`。
- `GET /chat/conversation/content` → `getConversationContent`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:591`。
- `POST /chat/conversation/list` → `listConversations`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:813`。
- `POST /chat/conversation/update` → `updateConversation`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:829`。

#### B093 · ChatController · 普通 · 2条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `POST /chat/library/search` → `searchLibrary`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:835`。
- `POST /chat/library/documents` → `saveLibraryDocument`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatController.java:863`。

#### B094 · ChatConversationArchiveController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `POST /chat/conversations/{conversationId}/archive-operations` → `archive`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatConversationArchiveController.java:55`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B095 · ChatConversationArchiveController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `GET /chat/conversations/{conversationId}/archive-operations/{operationId}` → `status`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatConversationArchiveController.java:70`。

#### B096 · ChatConversationAssetController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `GET /chat/conversations/{conversationId}/assets/{assetId}/content` → `content`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatConversationAssetController.java:61`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B097 · ChatConversationAssetController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `HEAD /chat/conversations/{conversationId}/assets/{assetId}/content` → `content`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatConversationAssetController.java:61`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B098 · ChatConversationAssetController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `GET /chat/conversations/{conversationId}/assets/{assetId}` → `detail`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatConversationAssetController.java:51`。

#### B099 · ChatTypedInspectionController · 普通 · 2条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `GET /chat/conversations/{conversationId}/interactions/inspection/request` → `recover`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatTypedInspectionController.java:59`。
- `GET /chat/conversations/{conversationId}/requests/{requestId}/inspection-outcome` → `outcome`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/ChatTypedInspectionController.java:72`。

#### B100 · JuyitingActionController · 普通 · 2条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `POST /juyiting/actions/{intentId}/dispatch` → `dispatch`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/JuyitingActionController.java:27`。
- `GET /juyiting/agents/{agentId}/mailbox` → `mailbox`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/api/JuyitingActionController.java:45`。

#### B101 · SpeechSynthesisController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `POST /chat/speech/synthesis` → `synthesize`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/voice/api/SpeechSynthesisController.java:37`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B102 · SpeechTranscriptionController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-chat.md`相应操作段。
- `POST /chat/speech/transcriptions` → `transcribe`；源码`chat/jia-chat-service/src/main/java/cn/jia/chat/voice/api/SpeechTranscriptionController.java:47`。

### dwz

#### B103 · DwzController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-dwz.md`相应操作段。
- `GET /dwz/view/{uri:.+}/**` → `view`；源码`dwz/jia-dwz-service/src/main/java/cn/jia/dwz/api/DwzController.java:35`。
- `GET /dwz/get` → `findById`；源码`dwz/jia-dwz-service/src/main/java/cn/jia/dwz/api/DwzController.java:64`。
- `POST /dwz/gen` → `gen`；源码`dwz/jia-dwz-service/src/main/java/cn/jia/dwz/api/DwzController.java:77`。
- `GET /dwz/restore` → `restore`；源码`dwz/jia-dwz-service/src/main/java/cn/jia/dwz/api/DwzController.java:90`。

#### B104 · DwzController · 普通 · 3条

- 预算：≤30分钟；交付位置：`http-dwz.md`相应操作段。
- `POST /dwz/update` → `update`；源码`dwz/jia-dwz-service/src/main/java/cn/jia/dwz/api/DwzController.java:103`。
- `DELETE /dwz/delete` → `delete`；源码`dwz/jia-dwz-service/src/main/java/cn/jia/dwz/api/DwzController.java:116`。
- `POST /dwz/list` → `list`；源码`dwz/jia-dwz-service/src/main/java/cn/jia/dwz/api/DwzController.java:129`。

### economy

#### B105 · EconomyWalletController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-economy.md`相应操作段。
- `GET /economy/wallet` → `wallet`；源码`economy/jia-economy-service/src/main/java/cn/jia/economy/api/EconomyWalletController.java:66`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B106 · EconomyWalletController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-economy.md`相应操作段。
- `GET /economy/ledger` → `ledger`；源码`economy/jia-economy-service/src/main/java/cn/jia/economy/api/EconomyWalletController.java:76`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B107 · EconomyWalletController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-economy.md`相应操作段。
- `POST /economy/onboarding-grant` → `claimOnboardingGrant`；源码`economy/jia-economy-service/src/main/java/cn/jia/economy/api/EconomyWalletController.java:88`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B108 · EconomyWalletController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-economy.md`相应操作段。
- `POST /economy/preview/issuances` → `issue`；源码`economy/jia-economy-service/src/main/java/cn/jia/economy/api/EconomyWalletController.java:102`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

### isp

#### B109 · CarController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-isp.md`相应操作段。
- `POST /car/list/brand` → `listBrand`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:40`。
- `GET /car/brand/get` → `findBrandById`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:57`。
- `POST /car/brand/create` → `createBrand`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:71`。
- `POST /car/brand/update` → `updateBrand`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:82`。

#### B110 · CarController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-isp.md`相应操作段。
- `GET /car/brand/delete` → `deleteBrand`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:93`。
- `POST /car/list/brandAudi` → `listBrandAudi`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:109`。
- `GET /car/brandAudi/get` → `findBrandAudiById`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:125`。
- `POST /car/brandAudi/create` → `createBrandAudi`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:139`。

#### B111 · CarController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-isp.md`相应操作段。
- `POST /car/brandAudi/update` → `updateBrandAudi`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:150`。
- `GET /car/brandAudi/delete` → `deleteBrandAudi`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:161`。
- `POST /car/list/brandVersion` → `listBrandVersion`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:177`。
- `GET /car/brandVersion/get` → `findBrandVersionById`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:193`。

#### B112 · CarController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-isp.md`相应操作段。
- `POST /car/brandVersion/create` → `createBrandVersion`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:207`。
- `POST /car/brandVersion/update` → `updateBrandVersion`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:218`。
- `GET /car/brandVersion/delete` → `deleteBrandVersion`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:229`。
- `POST /car/list/brandMf` → `listBrandMf`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:245`。

#### B113 · CarController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-isp.md`相应操作段。
- `GET /car/brandMf/get` → `findBrandMfById`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:261`。
- `POST /car/brandMf/create` → `createBrandMf`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:275`。
- `POST /car/brandMf/update` → `updateBrandMf`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:286`。
- `GET /car/brandMf/delete` → `deleteBrandMf`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CarController.java:297`。

#### B114 · CmsController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-isp.md`相应操作段。
- `POST /cms/table/list` → `listTable`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:48`。
- `GET /cms/table/get` → `findTableById`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:66`。
- `POST /cms/table/create` → `createTable`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:81`。
- `POST /cms/table/update` → `updateTable`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:93`。

#### B115 · CmsController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-isp.md`相应操作段。
- `GET /cms/table/delete` → `deleteTable`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:105`。
- `POST /cms/column/list` → `listColumn`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:118`。
- `GET /cms/column/get` → `findColumnById`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:135`。
- `POST /cms/column/create` → `createColumn`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:150`。

#### B116 · CmsController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-isp.md`相应操作段。
- `POST /cms/column/update` → `updateColumn`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:162`。
- `GET /cms/column/delete` → `deleteColumn`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:174`。
- `GET /cms/config/get` → `findConfig`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:185`。
- `POST /cms/config/update` → `updateConfig`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:197`。

#### B117 · CmsController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-isp.md`相应操作段。
- `POST /cms/row/list` → `listRow`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:227`。
- `POST /cms/row/get` → `findRowById`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:247`。
- `POST /cms/row/create` → `createRow`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:266`。
- `POST /cms/row/update` → `updateRow`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:317`。

#### B118 · CmsController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-isp.md`相应操作段。
- `POST /cms/row/delete` → `deleteRow`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/CmsController.java:405`。

#### B119 · FileController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-isp.md`相应操作段。
- `GET /file/res/{uri:.+}/**` → `findByURI`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/FileController.java:41`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B120 · IspController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-isp.md`相应操作段。
- `ANY /isp/dns/update_record` → `updateDnsRecord`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/IspController.java:163`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B121 · IspController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-isp.md`相应操作段。
- `POST /isp/server/list` → `listServer`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/IspController.java:47`。
- `GET /isp/server/get` → `findServerById`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/IspController.java:65`。
- `POST /isp/server/create` → `createServer`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/IspController.java:80`。
- `POST /isp/server/update` → `updateServer`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/IspController.java:93`。

#### B122 · IspController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-isp.md`相应操作段。
- `GET /isp/server/delete` → `deleteServer`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/IspController.java:105`。
- `GET /isp/server/ssl/refresh` → `refreshSSL`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/IspController.java:118`。
- `GET /isp/domain/record/delete` → `refreshSSL`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/IspController.java:124`。
- `GET /isp/dns/hosts` → `hosts`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/IspController.java:136`。

#### B123 · LdapController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-isp.md`相应操作段。
- `GET /ldap/user/get` → `userFind`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:59`。
- `POST /ldap/user/search` → `userSearch`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:86`。
- `POST /ldap/user/create` → `userCreate`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:104`。
- `POST /ldap/user/update` → `userUpdate`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:130`。

#### B124 · LdapController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-isp.md`相应操作段。
- `GET /ldap/user/delete` → `userDelete`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:144`。
- `GET /ldap/user/findAll` → `userFindAll`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:157`。
- `GET /ldap/usergroup/findByCn` → `userGroupFindByCn`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:174`。
- `GET /ldap/usergroup/findAll` → `userGroupFindAll`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:185`。

#### B125 · LdapController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-isp.md`相应操作段。
- `POST /ldap/usergroup/create` → `userGroupCreate`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:199`。
- `POST /ldap/usergroup/update` → `userGroupUpdate`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:227`。
- `POST /ldap/usergroup/member/add` → `userGroupMemberAdd`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:242`。
- `POST /ldap/usergroup/member/delete` → `userGroupMemberDelete`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:267`。

#### B126 · LdapController · 普通 · 2条

- 预算：≤30分钟；交付位置：`http-isp.md`相应操作段。
- `GET /ldap/usergroup/delete` → `userGroupDelete`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:290`。
- `GET /ldap/account/findByUid` → `accountFindByUid`；源码`isp/jia-isp-service/src/main/java/cn/jia/isp/api/LdapController.java:304`。

### kefu

#### B127 · KefuController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-kefu.md`相应操作段。
- `POST /kefu/message/create` → `createMessage`；源码`kefu/jia-kefu-service/src/main/java/cn/jia/kefu/api/KefuController.java:173`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B128 · KefuController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-kefu.md`相应操作段。
- `POST /kefu/image/upload` → `updateLogo`；源码`kefu/jia-kefu-service/src/main/java/cn/jia/kefu/api/KefuController.java:226`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B129 · KefuController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-kefu.md`相应操作段。
- `POST /kefu/faq/list` → `listFaq`；源码`kefu/jia-kefu-service/src/main/java/cn/jia/kefu/api/KefuController.java:55`。
- `GET /kefu/faq/get` → `findFaqById`；源码`kefu/jia-kefu-service/src/main/java/cn/jia/kefu/api/KefuController.java:76`。
- `POST /kefu/faq/create` → `createFaq`；源码`kefu/jia-kefu-service/src/main/java/cn/jia/kefu/api/KefuController.java:92`。
- `POST /kefu/faq/update` → `updateFaq`；源码`kefu/jia-kefu-service/src/main/java/cn/jia/kefu/api/KefuController.java:106`。

#### B130 · KefuController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-kefu.md`相应操作段。
- `GET /kefu/faq/delete` → `deleteFaq`；源码`kefu/jia-kefu-service/src/main/java/cn/jia/kefu/api/KefuController.java:119`。
- `POST /kefu/message/list` → `listMessage`；源码`kefu/jia-kefu-service/src/main/java/cn/jia/kefu/api/KefuController.java:136`。
- `GET /kefu/message/get` → `findMessageById`；源码`kefu/jia-kefu-service/src/main/java/cn/jia/kefu/api/KefuController.java:158`。
- `POST /kefu/message/update` → `updateMessage`；源码`kefu/jia-kefu-service/src/main/java/cn/jia/kefu/api/KefuController.java:195`。

#### B131 · KefuController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-kefu.md`相应操作段。
- `GET /kefu/message/delete` → `deleteMessage`；源码`kefu/jia-kefu-service/src/main/java/cn/jia/kefu/api/KefuController.java:208`。

### material

#### B132 · MediaController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-material.md`相应操作段。
- `POST /media/upload` → `upload`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/MediaController.java:143`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B133 · MediaController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-material.md`相应操作段。
- `GET /media/get` → `findById`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/MediaController.java:50`。
- `GET /media/get/content` → `findContentById`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/MediaController.java:66`。
- `POST /media/create` → `create`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/MediaController.java:80`。
- `POST /media/update` → `update`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/MediaController.java:93`。

#### B134 · MediaController · 普通 · 3条

- 预算：≤30分钟；交付位置：`http-material.md`相应操作段。
- `GET /media/delete` → `delete`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/MediaController.java:105`。
- `POST /media/list` → `list`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/MediaController.java:120`。
- `GET /media/thumbnail` → `thumbnail`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/MediaController.java:195`。

#### B135 · NewsController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-material.md`相应操作段。
- `GET /news/get` → `findById`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/NewsController.java:79`。
- `POST /news/create` → `create`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/NewsController.java:96`。
- `POST /news/update` → `update`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/NewsController.java:107`。
- `GET /news/delete` → `delete`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/NewsController.java:118`。

#### B136 · NewsController · 普通 · 2条

- 预算：≤30分钟；交付位置：`http-material.md`相应操作段。
- `POST /news/list` → `list`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/NewsController.java:128`。
- `GET /news/send` → `send`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/NewsController.java:153`。

#### B137 · PhraseController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-material.md`相应操作段。
- `GET /phrase/get` → `findById`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/PhraseController.java:26`。
- `POST /phrase/create` → `create`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/PhraseController.java:40`。
- `POST /phrase/update` → `update`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/PhraseController.java:51`。
- `GET /phrase/delete` → `delete`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/PhraseController.java:62`。

#### B138 · PhraseController · 普通 · 3条

- 预算：≤30分钟；交付位置：`http-material.md`相应操作段。
- `POST /phrase/get/random` → `getRandom`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/PhraseController.java:76`。
- `POST /phrase/vote` → `vote`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/PhraseController.java:90`。
- `GET /phrase/read` → `read`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/PhraseController.java:101`。

#### B139 · PvLogController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-material.md`相应操作段。
- `GET /pvlog/get` → `findById`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/PvLogController.java:42`。
- `POST /pvlog/create` → `create`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/PvLogController.java:57`。
- `POST /pvlog/update` → `update`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/PvLogController.java:78`。
- `DELETE /pvlog/delete` → `delete`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/PvLogController.java:90`。

#### B140 · PvLogController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-material.md`相应操作段。
- `POST /pvlog/list` → `list`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/PvLogController.java:101`。

#### B141 · TipController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-material.md`相应操作段。
- `GET /tip/get` → `findById`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/TipController.java:28`。
- `POST /tip/create` → `create`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/TipController.java:42`。
- `POST /tip/update` → `update`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/TipController.java:53`。
- `GET /tip/delete` → `delete`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/TipController.java:64`。

#### B142 · TipController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-material.md`相应操作段。
- `POST /tip/list` → `list`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/TipController.java:78`。

#### B143 · VoteController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-material.md`相应操作段。
- `GET /vote/get` → `findById`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/VoteController.java:30`。
- `POST /vote/create` → `create`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/VoteController.java:47`。
- `POST /vote/update` → `update`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/VoteController.java:58`。
- `GET /vote/delete` → `delete`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/VoteController.java:69`。

#### B144 · VoteController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-material.md`相应操作段。
- `POST /vote/list` → `list`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/VoteController.java:79`。
- `POST /vote/get/ticks` → `findTicks`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/VoteController.java:94`。
- `GET /vote/get/random` → `findRandom`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/VoteController.java:105`。
- `POST /vote/tick` → `tick`；源码`material/jia-material-service/src/main/java/cn/jia/mat/api/VoteController.java:117`。

### oauth

#### B145 · AuthenticationController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-oauth.md`相应操作段。
- `GET /token` → `token`；源码`oauth/jia-oauth-client-starter/src/main/java/cn/jia/oauth/api/AuthenticationController.java:12`。

#### B146 · OAuthResourceIdentityController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-oauth.md`相应操作段。
- `GET /resource` → `resource`；源码`oauth/jia-oauth-resource/src/main/java/cn/jia/oauth/api/OAuthResourceIdentityController.java:18`。

#### B147 · OauthController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-oauth.md`相应操作段。
- `GET /oauth/third-party/wxmp` → `thirdPartyWxMp`；源码`oauth/jia-oauth-service/src/main/java/cn/jia/oauth/api/OauthController.java:102`。
- `GET /oauth/third-party/weixin` → `thirdPartyWeiXin`；源码`oauth/jia-oauth-service/src/main/java/cn/jia/oauth/api/OauthController.java:182`。
- `GET /oauth/third-party/weibo` → `thirdPartyWeiBo`；源码`oauth/jia-oauth-service/src/main/java/cn/jia/oauth/api/OauthController.java:234`。
- `GET /oauth/third-party/github` → `thirdPartyGithub`；源码`oauth/jia-oauth-service/src/main/java/cn/jia/oauth/api/OauthController.java:316`。

#### B148 · OauthController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-oauth.md`相应操作段。
- `GET /oauth/third-party/autologin` → `thirdPartyAutoLogin`；源码`oauth/jia-oauth-service/src/main/java/cn/jia/oauth/api/OauthController.java:381`。
- `GET /oauth/confirm_access` → `getAccessConfirmation`；源码`oauth/jia-oauth-service/src/main/java/cn/jia/oauth/api/OauthController.java:513`。
- `GET /oauth/clientid` → `findClientId`；源码`oauth/jia-oauth-service/src/main/java/cn/jia/oauth/api/OauthController.java:540`。
- `GET /oauth/client/get` → `find`；源码`oauth/jia-oauth-service/src/main/java/cn/jia/oauth/api/OauthController.java:558`。

#### B149 · OauthController · 普通 · 2条

- 预算：≤30分钟；交付位置：`http-oauth.md`相应操作段。
- `POST /oauth/client/update` → `updateClient`；源码`oauth/jia-oauth-service/src/main/java/cn/jia/oauth/api/OauthController.java:574`。
- `GET /oauth/user` → `info`；源码`oauth/jia-oauth-service/src/main/java/cn/jia/oauth/api/OauthController.java:591`。

#### B150 · SessionRevocationController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-oauth.md`相应操作段。
- `POST /user/me/sessions/revoke-all` → `revokeAll`；源码`oauth/jia-oauth-resource/src/main/java/cn/jia/oauth/api/SessionRevocationController.java:25`。

### point

#### B151 · GiftController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-point.md`相应操作段。
- `POST /gift/usage/cancel/{giftUsageId}` → `usageCancel`；源码`point/jia-point-service/src/main/java/cn/jia/point/api/GiftController.java:114`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B152 · GiftController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-point.md`相应操作段。
- `GET /gift/get` → `findById`；源码`point/jia-point-service/src/main/java/cn/jia/point/api/GiftController.java:35`。
- `POST /gift/create` → `create`；源码`point/jia-point-service/src/main/java/cn/jia/point/api/GiftController.java:47`。
- `POST /gift/update` → `update`；源码`point/jia-point-service/src/main/java/cn/jia/point/api/GiftController.java:59`。
- `DELETE /gift/delete` → `delete`；源码`point/jia-point-service/src/main/java/cn/jia/point/api/GiftController.java:71`。

#### B153 · GiftController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-point.md`相应操作段。
- `POST /gift/list` → `list`；源码`point/jia-point-service/src/main/java/cn/jia/point/api/GiftController.java:82`。
- `POST /gift/usage/add` → `usageAdd`；源码`point/jia-point-service/src/main/java/cn/jia/point/api/GiftController.java:98`。
- `POST /gift/usage/delete/{giftUsageId}` → `usageDelete`；源码`point/jia-point-service/src/main/java/cn/jia/point/api/GiftController.java:126`。
- `POST /gift/usage/list/gift/{giftId}` → `usageListByGift`；源码`point/jia-point-service/src/main/java/cn/jia/point/api/GiftController.java:139`。

#### B154 · GiftController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-point.md`相应操作段。
- `POST /gift/usage/list/user/{user}` → `usageListByUser`；源码`point/jia-point-service/src/main/java/cn/jia/point/api/GiftController.java:155`。

#### B155 · PointController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-point.md`相应操作段。
- `POST /point/init` → `init`；源码`point/jia-point-service/src/main/java/cn/jia/point/api/PointController.java:29`。
- `POST /point/sign` → `sign`；源码`point/jia-point-service/src/main/java/cn/jia/point/api/PointController.java:42`。
- `POST /point/referral` → `referral`；源码`point/jia-point-service/src/main/java/cn/jia/point/api/PointController.java:55`。
- `POST /point/luck` → `luck`；源码`point/jia-point-service/src/main/java/cn/jia/point/api/PointController.java:68`。

### sms

#### B156 · SmsController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-sms.md`相应操作段。
- `GET /sms/validate` → `validateSmsCode`；源码`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:53`。
- `GET /sms/use` → `useSmsCode`；源码`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:72`。
- `GET /sms/gen` → `gen`；源码`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:92`。
- `POST /sms/send` → `sendSms`；源码`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:137`。

#### B157 · SmsController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-sms.md`相应操作段。
- `POST /sms/send/list` → `listSend`；源码`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:172`。
- `POST /sms/sendBatch` → `sendSmsBatch`；源码`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:190`。
- `GET /sms/balance` → `balance`；源码`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:225`。
- `GET /sms/receive` → `receive`；源码`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:242`。

#### B158 · SmsController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-sms.md`相应操作段。
- `POST /sms/reply/list` → `listReply`；源码`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:262`。
- `GET /sms/config/get` → `findConfig`；源码`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:278`。
- `POST /sms/config/update` → `updateConfig`；源码`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:291`。
- `GET /sms/template/get` → `findTemplateById`；源码`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:306`。

#### B159 · SmsController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-sms.md`相应操作段。
- `POST /sms/template/create` → `createTemplate`；源码`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:322`。
- `POST /sms/template/update` → `updateTemplate`；源码`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:335`。
- `GET /sms/template/delete` → `deleteTemplate`；源码`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:348`。
- `POST /sms/template/list` → `listTemplate`；源码`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:362`。

#### B160 · SmsController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-sms.md`相应操作段。
- `GET /sms/buy` → `buy`；源码`sms/jia-sms-service/src/main/java/cn/jia/sms/api/SmsController.java:381`。

### task

#### B161 · JobController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-task.md`相应操作段。
- `GET /job/list` → `list`；源码`task/jia-task-service/src/main/java/cn/jia/task/api/JobController.java:37`。
- `GET /job/get` → `get`；源码`task/jia-task-service/src/main/java/cn/jia/task/api/JobController.java:50`。
- `POST /job/execute` → `execute`；源码`task/jia-task-service/src/main/java/cn/jia/task/api/JobController.java:72`。
- `POST /job/execute/async` → `executeAsync`；源码`task/jia-task-service/src/main/java/cn/jia/task/api/JobController.java:88`。

#### B162 · JobController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-task.md`相应操作段。
- `GET /job/status` → `status`；源码`task/jia-task-service/src/main/java/cn/jia/task/api/JobController.java:104`。

#### B163 · TaskController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-task.md`相应操作段。
- `DELETE /task/cancel` → `cancel`；源码`task/jia-task-service/src/main/java/cn/jia/task/api/TaskController.java:89`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B164 · TaskController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-task.md`相应操作段。
- `GET /task/get` → `findById`；源码`task/jia-task-service/src/main/java/cn/jia/task/api/TaskController.java:36`。
- `POST /task/create` → `create`；源码`task/jia-task-service/src/main/java/cn/jia/task/api/TaskController.java:49`。
- `POST /task/update` → `update`；源码`task/jia-task-service/src/main/java/cn/jia/task/api/TaskController.java:63`。
- `DELETE /task/delete` → `delete`；源码`task/jia-task-service/src/main/java/cn/jia/task/api/TaskController.java:76`。

#### B165 · TaskController · 普通 · 2条

- 预算：≤30分钟；交付位置：`http-task.md`相应操作段。
- `POST /task/search` → `search`；源码`task/jia-task-service/src/main/java/cn/jia/task/api/TaskController.java:101`。
- `POST /task/item/search` → `searchItem`；源码`task/jia-task-service/src/main/java/cn/jia/task/api/TaskController.java:117`。

### user

#### B166 · GroupController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-user.md`相应操作段。
- `GET /group/get` → `findById`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/GroupController.java:39`。
- `POST /group/get/users` → `findUsers`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/GroupController.java:55`。
- `POST /group/get/roles` → `findRoles`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/GroupController.java:72`。
- `POST /group/create` → `create`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/GroupController.java:89`。

#### B167 · GroupController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-user.md`相应操作段。
- `POST /group/update` → `update`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/GroupController.java:102`。
- `DELETE /group/delete` → `delete`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/GroupController.java:115`。
- `POST /group/list` → `list`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/GroupController.java:127`。
- `POST /group/users/add` → `userBatchAdd`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/GroupController.java:144`。

#### B168 · GroupController · 普通 · 2条

- 预算：≤30分钟；交付位置：`http-user.md`相应操作段。
- `POST /group/users/del` → `userBatchDel`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/GroupController.java:157`。
- `POST /group/role/change` → `changeRole`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/GroupController.java:170`。

#### B169 · LoginController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-user.md`相应操作段。
- `POST /login/user/avatar/update` → `updateAvatar`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/LoginController.java:425`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B170 · LoginController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-user.md`相应操作段。
- `GET /login/index.html` → `login`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/LoginController.java:95`。
- `GET /login/sms/gen` → `gen`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/LoginController.java:178`。
- `GET /login/register` → `register`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/LoginController.java:215`。
- `POST /login/register` → `register`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/LoginController.java:222`。

#### B171 · LoginController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-user.md`相应操作段。
- `GET /login/resetPassword` → `resetPassword`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/LoginController.java:256`。
- `POST /login/resetPassword` → `resetPassword`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/LoginController.java:329`。
- `GET /login/user` → `info`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/LoginController.java:361`。
- `GET /login/user/info` → `userInfo`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/LoginController.java:366`。

#### B172 · LoginController · 普通 · 2条

- 预算：≤30分钟；交付位置：`http-user.md`相应操作段。
- `POST /login/user/info/update` → `updateUser`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/LoginController.java:383`。
- `GET /login/user/password/change` → `changePassword`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/LoginController.java:403`。

#### B173 · MsgController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-user.md`相应操作段。
- `GET /msg/get` → `findById`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/MsgController.java:30`。
- `POST /msg/create` → `create`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/MsgController.java:43`。
- `GET /msg/read` → `read`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/MsgController.java:56`。
- `GET /msg/unread` → `unread`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/MsgController.java:71`。

#### B174 · MsgController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-user.md`相应操作段。
- `GET /msg/readall` → `readAll`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/MsgController.java:86`。
- `DELETE /msg/delete` → `delete`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/MsgController.java:99`。
- `GET /msg/recycle` → `recycle`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/MsgController.java:111`。
- `GET /msg/restore` → `restore`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/MsgController.java:123`。

#### B175 · MsgController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-user.md`相应操作段。
- `POST /msg/list` → `list`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/MsgController.java:135`。

#### B176 · OrgController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-user.md`相应操作段。
- `POST /org/update/logo` → `updateLogo`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/OrgController.java:273`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B177 · OrgController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-user.md`相应操作段。
- `GET /org/get` → `findAllById`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/OrgController.java:57`。
- `GET /org/get/parent` → `findParent`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/OrgController.java:86`。
- `GET /org/get/name` → `findNameById`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/OrgController.java:102`。
- `POST /org/get/users` → `findUsers`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/OrgController.java:117`。

#### B178 · OrgController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-user.md`相应操作段。
- `POST /org/create` → `create`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/OrgController.java:134`。
- `POST /org/update` → `update`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/OrgController.java:146`。
- `DELETE /org/delete` → `delete`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/OrgController.java:158`。
- `POST /org/list` → `list`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/OrgController.java:169`。

#### B179 · OrgController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-user.md`相应操作段。
- `POST /org/list/sub` → `listSub`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/OrgController.java:185`。
- `POST /org/users/add` → `userBatchAdd`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/OrgController.java:202`。
- `POST /org/users/del` → `userBatchDel`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/OrgController.java:225`。
- `GET /org/get/director` → `findDirector`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/OrgController.java:243`。

#### B180 · PermsController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-user.md`相应操作段。
- `GET /action/get` → `findById`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/PermsController.java:34`。
- `POST /action/create` → `create`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/PermsController.java:47`。
- `POST /action/update` → `update`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/PermsController.java:60`。
- `POST /action/refresh` → `refresh`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/PermsController.java:73`。

#### B181 · PermsController · 普通 · 2条

- 预算：≤30分钟；交付位置：`http-user.md`相应操作段。
- `DELETE /action/delete` → `delete`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/PermsController.java:86`。
- `POST /action/list` → `list`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/PermsController.java:98`。

#### B182 · RoleController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-user.md`相应操作段。
- `GET /role/get` → `findById`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/RoleController.java:47`。
- `POST /role/get/users` → `findUsers`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/RoleController.java:59`。
- `POST /role/get/perms` → `findPerms`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/RoleController.java:76`。
- `POST /role/create` → `create`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/RoleController.java:95`。

#### B183 · RoleController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-user.md`相应操作段。
- `POST /role/update` → `update`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/RoleController.java:107`。
- `DELETE /role/delete` → `delete`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/RoleController.java:119`。
- `POST /role/list` → `list`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/RoleController.java:131`。
- `POST /role/perms/change` → `changePerms`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/RoleController.java:147`。

#### B184 · RoleController · 普通 · 2条

- 预算：≤30分钟；交付位置：`http-user.md`相应操作段。
- `POST /role/users/add` → `userBatchAdd`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/RoleController.java:159`。
- `POST /role/users/del` → `userBatchDel`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/RoleController.java:172`。

#### B185 · UserController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-user.md`相应操作段。
- `POST /user/batch/import` → `batchImport`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/UserController.java:400`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B186 · UserController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-user.md`相应操作段。
- `POST /user/update/avatar` → `updateAvatar`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/UserController.java:475`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B187 · UserController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-user.md`相应操作段。
- `GET /user/get` → `find`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/UserController.java:76`。
- `GET /user/get/name` → `findNameById`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/UserController.java:110`。
- `POST /user/get/roles` → `findRoles`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/UserController.java:127`。
- `GET /user/get/orgs` → `findOrgs`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/UserController.java:145`。

#### B188 · UserController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-user.md`相应操作段。
- `GET /user/check` → `check`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/UserController.java:159`。
- `POST /user/create` → `create`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/UserController.java:183`。
- `POST /user/update` → `update`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/UserController.java:196`。
- `POST /user/sync` → `sync`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/UserController.java:209`。

#### B189 · UserController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-user.md`相应操作段。
- `DELETE /user/delete` → `delete`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/UserController.java:222`。
- `POST /user/list` → `list`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/UserController.java:235`。
- `POST /user/search` → `search`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/UserController.java:265`。
- `GET /user/point/change` → `changePoint`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/UserController.java:287`。

#### B190 · UserController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-user.md`相应操作段。
- `POST /user/role/change` → `changeRole`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/UserController.java:300`。
- `POST /user/group/change` → `changeGroup`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/UserController.java:313`。
- `POST /user/org/change` → `changeOrg`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/UserController.java:326`。
- `GET /user/my` → `userInfo`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/UserController.java:339`。

#### B191 · UserController · 普通 · 3条

- 预算：≤30分钟；交付位置：`http-user.md`相应操作段。
- `GET /user/password/change` → `changePassword`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/UserController.java:363`。
- `GET /user/password/reset` → `resetPassword`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/UserController.java:379`。
- `GET /user/position/change` → `changePosition`；源码`user/jia-user-service/src/main/java/cn/jia/user/api/UserController.java:422`。

### workflow

#### B192 · WorkflowController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-workflow.md`相应操作段。
- `POST /workflow/deploy` → `deployProcess`；源码`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:53`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B193 · WorkflowController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-workflow.md`相应操作段。
- `POST /workflow/start/{processDefinitionKey}/{businessKey}` → `startProcess`；源码`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:269`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B194 · WorkflowController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-workflow.md`相应操作段。
- `POST /workflow/list/process` → `listHistoryByBusinessKey`；源码`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:370`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B195 · WorkflowController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-workflow.md`相应操作段。
- `POST /workflow/complete/{taskId}` → `complete`；源码`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:462`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B196 · WorkflowController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-workflow.md`相应操作段。
- `POST /workflow/deployment/list` → `getDeployment`；源码`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:69`。
- `GET /workflow/deployment/find` → `getDeploymentById`；源码`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:95`。
- `GET /workflow/deployment/resource/list` → `getDeploymentResourceNames`；源码`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:114`。
- `GET /workflow/deployment/resource/find` → `getDeploymentResource`；源码`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:132`。

#### B197 · WorkflowController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-workflow.md`相应操作段。
- `GET /workflow/deployment/delete` → `deleteDeployment`；源码`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:152`。
- `POST /workflow/definition/list` → `getDefinition`；源码`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:164`。
- `GET /workflow/definition/find` → `getDefinitionById`；源码`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:195`。
- `GET /workflow/definition/diagram` → `getProcessDiagram`；源码`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:223`。

#### B198 · WorkflowController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-workflow.md`相应操作段。
- `GET /workflow/definition/activate` → `activateDefinition`；源码`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:243`。
- `GET /workflow/definition/suspend` → `suspendDefinition`；源码`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:255`。
- `POST /workflow/delete/{processInstanceId}` → `deleteProcess`；源码`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:285`。
- `POST /workflow/list/wait` → `listWait`；源码`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:297`。

#### B199 · WorkflowController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-workflow.md`相应操作段。
- `POST /workflow/list/history` → `listHistory`；源码`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:335`。
- `POST /workflow/list/my` → `listHistoricProcessInstances`；源码`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:412`。
- `GET /workflow/task/delegate` → `delegateTask`；源码`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:490`。
- `GET /workflow/task/claim` → `claimTask`；源码`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:502`。

#### B200 · WorkflowController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-workflow.md`相应操作段。
- `GET /workflow/task/assign` → `setAssignee`；源码`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:515`。
- `GET /workflow/find/bybusinesskey` → `getTaskByBusinessKey`；源码`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:528`。
- `GET /workflow/list/bybusinesskey` → `listTaskByBusinessKey`；源码`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:560`。
- `GET /workflow/find` → `getTaskById`；源码`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:587`。

#### B201 · WorkflowController · 普通 · 1条

- 预算：≤30分钟；交付位置：`http-workflow.md`相应操作段。
- `GET /workflow/instance/diagram` → `getInstanceDiagram`；源码`workflow/jia-workflow-service/src/main/java/cn/jia/workflow/api/WorkflowController.java:621`。

### wx

#### B202 · WxMpController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `POST /wx/mp/checksignature` → `receiveMsg`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:153`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B203 · WxMpController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `ANY /wx/mp/menu/create` → `menuCreate`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:700`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B204 · WxMpController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `ANY /wx/mp/menu/get` → `menuGet`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:715`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B205 · WxMpController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `ANY /wx/mp/menu/delete` → `menuDelete`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:727`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B206 · WxMpController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `ANY /wx/mp/user/get` → `userGet`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:741`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B207 · WxMpController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `ANY /wx/mp/user/info/batchget` → `userInfoBatchGet`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:754`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B208 · WxMpController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `ANY /wx/mp/user/sync` → `userSync`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:766`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B209 · WxMpController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `ANY /wx/mp/oauth2/authorize_url` → `oauth2Authorize`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:819`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B210 · WxMpController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `ANY /wx/mp/oauth2/access_token` → `oauth2AccessToken`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:834`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B211 · WxMpController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `ANY /wx/mp/oauth2/userinfo` → `oauth2Userinfo`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:849`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B212 · WxMpController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `ANY /wx/mp/oauth2/refresh_token` → `oauth2RefreshToken`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:868`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B213 · WxMpController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `ANY /wx/mp/oauth2/auth` → `oauth2ValidateAccessToken`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:883`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B214 · WxMpController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `ANY /wx/mp/jsapi/signature` → `createJsapiSignature`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:900`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B215 · WxMpController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `ANY /wx/mp/customservice/getkflist` → `getkflist`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:912`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B216 · WxMpController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `ANY /wx/mp/customservice/kfsession/getwaitcase` → `getwaitcase`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:924`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B217 · WxMpController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `ANY /wx/mp/message/custom/send` → `customMessageSend`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:937`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B218 · WxMpController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `ANY /wx/mp/message/template/send` → `templateMessageSend`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:950`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B219 · WxMpController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `ANY /wx/mp/material/batchget` → `materialBatchGet`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:965`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B220 · WxMpController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `ANY /wx/mp/material/add_news` → `materialAddNews`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:984`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B221 · WxMpController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `ANY /wx/mp/material/add_material` → `materialAdd`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:1035`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B222 · WxMpController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `GET /wx/mp/checksignature` → `checkSignature`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:134`。
- `GET /wx/mp/info/get` → `findMpInfoById`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:1060`。
- `POST /wx/mp/info/create` → `createMpInfo`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:1075`。
- `POST /wx/mp/info/update` → `updateMpInfo`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:1088`。

#### B223 · WxMpController · 普通 · 2条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `GET /wx/mp/info/delete` → `deleteMpInfo`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:1100`。
- `POST /wx/mp/info/list` → `listMpInfo`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxMpController.java:1113`。

#### B224 · WxPayController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `GET /wx/pay/queryOrder` → `queryOrder`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:83`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B225 · WxPayController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `GET /wx/pay/closeOrder/{outTradeNo}` → `closeOrder`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:105`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B226 · WxPayController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `POST /wx/pay/createOrder` → `createOrder`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:116`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B227 · WxPayController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `GET /wx/pay/createOrder` → `createOrder`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:132`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B228 · WxPayController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `POST /wx/pay/unifiedOrder` → `unifiedOrder`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:246`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B229 · WxPayController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `GET /wx/pay/unifiedOrder` → `unifiedOrder`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:260`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B230 · WxPayController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `POST /wx/pay/refund` → `refund`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:304`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B231 · WxPayController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `GET /wx/pay/refundQuery` → `refundQuery`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:332`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B232 · WxPayController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `POST /wx/pay/parseOrderNotifyResult` → `parseOrderNotifyResult`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:344`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B233 · WxPayController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `POST /wx/pay/parseRefundNotifyResult` → `parseRefundNotifyResult`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:374`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B234 · WxPayController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `POST /wx/pay/parseScanPayNotifyResult` → `parseScanPayNotifyResult`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:386`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B235 · WxPayController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `POST /wx/pay/scanPay/qrcode` → `createScanPayQrcodeMode1`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:514`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B236 · WxPayController · 专项 · 1条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `POST /wx/pay/reverseOrder` → `reverseOrder`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:650`。
- 本包额外核验：按该接口实际媒体类型/请求解析/副作用，写明特殊传输、幂等或method限定；不能将ANY自动当GET。

#### B237 · WxPayController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `POST /wx/pay/sendRedpack` → `sendRedpack`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:437`。
- `GET /wx/pay/queryRedpack/{mchBillNo}` → `queryRedpack`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:455`。
- `POST /wx/pay/entPay` → `entPay`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:474`。
- `GET /wx/pay/queryEntPay/{partnerTradeNo}` → `queryEntPay`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:491`。

#### B238 · WxPayController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `GET /wx/pay/scanPay/qrcodeLink` → `createScanPayQrcodeMode1`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:542`。
- `POST /wx/pay/report` → `report`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:582`。
- `GET /wx/pay/downloadBill` → `downloadBill`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:613`。
- `POST /wx/pay/micropay` → `micropay`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:632`。

#### B239 · WxPayController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `GET /wx/pay/getSandboxSignKey` → `getSandboxSignKey`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:655`。
- `POST /wx/pay/sendCoupon` → `sendCoupon`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:660`。
- `POST /wx/pay/queryCouponStock` → `queryCouponStock`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:665`。
- `POST /wx/pay/queryCouponInfo` → `queryCouponInfo`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:671`。

#### B240 · WxPayController · 普通 · 4条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `POST /wx/pay/queryComment` → `queryComment`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:677`。
- `GET /wx/pay/info/get` → `findPayInfoById`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:687`。
- `POST /wx/pay/info/create` → `createPayInfo`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:701`。
- `POST /wx/pay/info/update` → `updatePayInfo`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:712`。

#### B241 · WxPayController · 普通 · 2条

- 预算：≤30分钟；交付位置：`http-wx.md`相应操作段。
- `GET /wx/pay/info/delete` → `deletePayInfo`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:723`。
- `POST /wx/pay/info/list` → `listPayInfo`；源码`wx/jia-wx-service/src/main/java/cn/jia/wx/api/WxPayController.java:734`。

## 7. 交叉遗漏/最终收口：8包

| 包 | 范围 | 本包交付 |
| --- | --- | --- |
| F01 | HTTP公共错误与特殊响应收口 | 真实状态码/202/HEAD/Range/秘密不可重放补齐到公共规范；不重新讨论既定信封 |
| F02 | 框架/条件装配遗漏清单 | OAuth/Actuator/error、继承/组合注解、context-path/代理挂载的入口与核验方式；不启动生产查询 |
| F03 | Web消费者定位 | 以本轮改动项建立useHttp、直接fetch、stream及特殊客户端的源码映射；不改前端代码 |
| F04 | Runtime与运维消费者定位 | 定位HTTP/WS/UDS/parser及脚本引用，记录固定版本与接入方式；不读秘密 |
| F05 | 跨端类型/字段冲突收口 | 检查同名ID、revision、时间、摘要、金额及实际mapper路径；引用各包字段表 |
| F06 | 持久消息/切换恢复详设收口 | 明确对象/原字节/hash/在途盘点字段、切换与回退步骤；不盘改生产数据 |
| F07 | 契约样例与测试映射收口 | 为已定义操作列serializer→parser fixture位置/正反例/selector；本包只索引核对，不声称编写全部机器schema/执行测试 |
| F08 | 文档完整性与状态收口 | 路由覆盖不重不漏、字段缺口、链接/JSON/源码摘要、handoff；未闭合留未完成 |

## 8. 工作量口径

- K：25包；N：11包；B：241包；F：8包；**当前共285个预算工作包**。
- 69条关键链与527条其他接口合计596条：B覆盖527条且无重复；K按原关键接口编号覆盖69条。N是消息协议，不与HTTP路由数混加。
- 每包30分钟预算累计为142.5人时，仅为当前拆分的预算总额，**不是实测工期、承诺或全项目上限**；复用共享DTO可缩短，未知复杂项拆分会增加，不据此承诺某日完成。
- 全量机器schema/fixture实现、生产消费者与在途核验、代码迁移、正式测试发布另计；不把它们偷偷塞入F07/F08后宣称全量完成。
- 当前先做关键链25包，不要求等全系统527条都写完才修图片功能；如果只核对某一条交付链，按该链真实消费者确定范围，不虚构一个小接口数量。
