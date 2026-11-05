# CHAT 上下文增量投递（候选，未启用）

核对日期：2026-11-05。API 基线 `5b96cd8d6ed0127e130c569b31b133d129e57a65`；bridge 基线 `54c47019aa9ff7bd6e53181cfa9c6c9a073ea710`。适用 CYF 根规则、接口规范当前工作区 v1.4、RB-02/03。本文件描述候选增量字段，不改变公共消息信封。源码未合入/发布；派发端尚未生成下述字段。

## 责任和正常路径

- API 选择获授权历史，在该会话/好汉前序处理结束、首次派发前固定输入；重传不修改快照和摘要。
- bridge 使用 API 的计划，不保存正文指纹、不筛选历史、不增加历史扫描或确认 RPC。
- 原生 thread 保存模型历史；业务、授权、材料事实仍来自 API。
- 完整最终回复不变，附带本次原生完成的上下文回执；API 在原最终回复事务内保存。没有逐帧进度写入、独立 ACK、独立提交事务、新表或轮询。

## 候选字段

`contextSnapshot.facts.contextDelivery`：

| 字段 | 语义 |
| --- | --- |
| schemaVersion | 数字 1；允许无害扩展字段，不猜测未知模式 |
| mode | BOOTSTRAP 或 DELTA |
| baseTurnId | DELTA 对应的已完成业务 turn；BOOTSTRAP 为 null |
| engineThreadId | DELTA 必须使用的原生 thread；BOOTSTRAP 为 null |
| afterMessageId / throughMessageId | 十进制字符串，不能使用 JS 浮点数；候选已固定的输入边界，不是最终回复 ID |

最终回复业务数据的 `contextReceipt`：`schemaVersion`、`mode`、真实 `engineThreadId`、真实 `engineTurnId`、原计划 `throughMessageId`。回执属于最终回复，不是新消息种类。控制字段从模型输入 envelope 移除，但保留在源快照及其摘要中。

完整共享示例：`chat/jia-chat-core/src/test/resources/contracts/chat-context-delivery-v1.json`；bridge 对应 `conf/codex-bridge/contracts/chat-context-delivery-v1.json`。示例由 Java producer 实际生成，包含大于 2^53 的消息 ID。

## 已接入的最终回复消费端

`AgentWebSocketHandler` 将回执交给 `ChatDeliberationService.persistFinal`。后者在已锁定、已授权、已绑定的快照上验证一次计划与回执，并将归一化回执存入既有最终消息 metadata。

- 首次成功：仍是原消息 INSERT 和 turn/outbox 等既有写入；不增加进度 INSERT/UPDATE 或查询。
- 幂等重复：仅含新计划的重复 final 读取其已持久化最终消息，对比回执；相同正文但不同原生完成身份不能覆盖进度。
- 不改业务 `finalDigest` 的算法：操作继续/成果链路仍使用原摘要，不因新增上下文控制字段失配。
- 没有计划的旧快照、没有回执的旧最终回复仍按原流程处理；不据此构造原生进度。

这只是消费端接线；尚无派发端启用、进度查询或数据库事务集成验收证据。

## 必要校验与失败范围

| 责任层/条件 | 具体风险 | 失败范围 | 定向回归 |
| --- | --- | --- | --- |
| bridge：DELTA 绑定不是目标 thread | 空会话执行缺失历史的请求 | turn/start 前拒绝；不能执行后自动重跑 | chat-runtime.test.mjs 的 delta context |
| bridge：原生结果非已完成或 thread/turn 不匹配 | 为未确认执行伪造进度 | 保留恢复状态，不发布成功回执、不再次调用模型 | 同上；材料原恢复测试 |
| API：新计划缺回执、模式/边界/thread 不匹配 | 错误推进或跨会话进度 | 当前最终事务失败；不写成果/进度 | ChatContextReceiptFinalTest、ChatContextDeliveryTest |
| API：重复最终回复改变原生 turn | 将不同执行伪装成同一已完成请求 | 拒绝该重复，不覆盖已存结果 | ChatContextReceiptFinalTest |

身份/ACL/派发绑定复用现有入口和业务层，不在该合同类重做。

## 派发端启用前必须完成（未完成）

1. 移走当前准入阶段的重复历史读取/冻结，利用既有 outbox 和完成唤醒按 lane 准备；不能只是 bridge 删除正文。
2. 核对所有消息写入口和可见性顺序。例：用户 U1=100、用户 U2=101、U1 的回复 A1=102。U1 完成不得把游标推进为 102，否则 U2 会被跳过。新 thread bootstrap 还须包含已完成的 A1，不能机械用 `id<=U2` 截断历史。
3. 现有 summary/recent 的有界截断、仅哈希条目不等于完整消息已交付；必须明确投递覆盖范围，不能把 `sourceMessageIds` 当完整消费确认。
4. 原生会话缺失需要显式的“未执行重新准备”。当前 bridge 的 `CHAT_CONTEXT_BOOTSTRAP_REQUIRED` 只会阻止执行；API 尚未接恢复分支。不能修改已发送消息的 snapshot/contextHash，不能用新 ID 重跑可能已执行的工具。
5. 同原生 thread 的自身最终回复按精确业务 turn/原生结果关联排除；不能跳过全部 ASSISTANT，也不能由 bridge 猜测。
6. 定向服务/SQL/事务及跨进程恢复测试在固定源码 Flow 验证；通过之后才能启用并做新会话多轮实测。

验证区分：本地 Java 单类/Node 合同验证不是 Spring/SQL 验收；mock 服务测试也不是数据库回滚证明；不存在已测提速结论。

## 2026-11-05 接续：派发准备候选实现

以下更新上节相应“待接线”的源码状态，但**准入端尚未生成 PENDING 草稿，未合入、未启用/发布**。

- `ChatContextHistoryMapper` 已分别按请求顺序和消息顺序取增量。用户输入的下界来自 `baseTurnId` 对应已完成请求的 `chat_request.id`，上界是当前请求的 `id`；其余消息使用 `afterMessageId/throughMessageId`。不新增第二份用户历史或额外 wire 游标。即使排队用户的消息 ID 低于已保存水位，它仍由请求顺序取到；动作继续复用旧 USER 时，当前输入仍独立发送。
- `throughMessageId` 取实际准备时的已提交消息上界，而不是当前 USER 或最终 ASSISTANT 的 ID。DELTA 允许 `after==through`；它不代表没有新请求。BOOTSTRAP 仍按当前既有有界历史策略选择，不承诺已把每个旧正文完整传入模型。
- BOOTSTRAP 走最新消息的有界查询，不先物化整个历史请求 ID 集。DELTA 只合并缺失请求的 USER 和新增的非请求消息。仅排除已完成前序记录的精确 `final_message_id`：该回复已在对应原生 thread 中；不跳过所有 ASSISTANT。
- `prepareContextForClaim` 运行在既有 outbox `claim` 事务中，不另开事务。依次锁会话、turn、outbox；前序未终结返回等待。首次发送前更新原快照内容、turn 的摘要和 outbox 内容，保持快照 ID 不变；移除内部 `contextPreparation/contextInputRefs` 后固定内容。已固定内容的重新领取不查询历史、不修改摘要。
- 避免多余读：复用 outbox 已携带的事实与源向量；复用已经准入的当前 USER 正文；前序请求顺序和最终回执通过一次关联查询读取，不重新读取/校验其旧快照。领取方复用准备过程已锁定的 outbox 行，不再次 SELECT FOR UPDATE。
- outbox 发现和下一次定时唤醒查询都在 LIMIT/聚合前排除被同会话、同好汉前序阻塞的派发；已发送的 ACK 重试、租约恢复及非派发事件不混成上下文重准备。终态事务通过原 after-commit 唤醒提示放行，不新增定时任务。
- 源码增加 `chat_request` 的请求顺序和 `user_message_id` 两个查询索引定义，schema initializer 同步；无新表、无 CHECK，未对生产执行 DDL。它们避免请求窗口及消息归属关联走无索引扫描，实际规模下的执行计划/耗时仍需正式验证。
- **成本边界**：最终回执仍无额外正常写库；首次派发准备会更新三个既有记录，且有顺序/历史查询。这里只是合并到既有事务，并非“零新增 SQL”；是否降低总体 API 压力须在准入端移除原历史查询后比较，不能只报 token 减少。

已检查消息写入口：现代准入/最终/取消部分历史、悬赏交互/首次接纳、成果投影、`appendOwnedMessage`（含 DatabaseChatMemoryAdvisor）都使用会话锁；`AgentTaskThreadCreationTransaction` 属于 `task_thread`，不是该议事准入支持的 public/private/bounty，未顺带改造。新增入口若要加入此投递链，必须遵循同一锁顺序；不把全局自增天然等同提交顺序。

当前证据：单类合同及两个 Mapper 隔离编译、实际 MyBatis SQL 渲染、只读 MySQL 合成 CTE 16 项通过；没有读取/修改生产业务行。新增四项真实 Spring/MyBatis 事务测试位于既有 `r8TenantPrivateChat`，覆盖排队、首次补全、准备回滚、重传不重组，尚未在 Flow 执行。本地合成查询不是数据库回滚验收、应用编译或索引规模测试。

仍未完成：准入阶段只保存当前输入/固定业务事实而不构建历史；确认未执行的原生会话丢失重准备；最新业务事实的冻结边界与材料/动作消费者最后接线；Flow 全部受影响回归及性能实测。不要单独上线这些候选字段/路径。

### 2026-11-05 接续：准入幂等开销和未执行错误边界

候选基线 API `5b96cd8d6ed0127e130c569b31b133d129e57a65`；以下为未提交源码状态，不是上线证明。

- 准入先按已锁定会话授权 conversation/task 引用，再核对请求摘要/幂等身份；没有 message 引用的重复请求直接使用保存的快照，不再查询历史正文或当前任务详情。确有 message 引用时，仍使用原授权历史窗口，不放宽为任意消息 ID；新请求复用这次查询，不查询两次。新请求的全量历史尚未迁出准入，不能把本项称为增量已启用。
- bridge 的 `CHAT_CONTEXT_BOOTSTRAP_REQUIRED` 只能来自执行前的绑定检查。完成回执构造/重建时若原生 thread 不匹配，改用既有 `TURN_ACCEPTANCE_UNKNOWN`，不得以“未执行”重新准备。此前候选共用同一校验函数，会让这两种阶段混用错误码；已在普通及 action final 定向测试覆盖。
- 新增/增强 `ChatReviewContextAdmissionTest` 的重放不查历史/任务、scope 引用、message 引用单次读取与越界拒绝测试；真实事务 fixture 新增重复准入复用保存快照测试。Java 测试尚未在 Flow 编译执行。
- 轻量 Runtime 合同测试 10 项及 runFastChat 定向测试 2 项通过，证据见 handoff。此处不代表 API 事务、自动补全恢复或整体性能验收。

### 2026-11-05 接续：明确未执行的完整上下文重准备（源码候选）

以下仅代表源码接线；尚未正式 Flow 验证、准入激活、迁移或部署。

- Runtime 在既有 inbox 恢复记录内保存 `contextRejection={schemaVersion:1,code:CHAT_CONTEXT_BOOTSTRAP_REQUIRED,executionStarted:false}`。仅 CHAT/DELTA、STARTING 且没有 preparation/engine/final 证据的路径能写入。原有 `chat.dispatch.ack` 携带相同 code 和布尔 `executionStarted:false`，不增加消息种类、定时器或额外确认协议；重连沿原恢复遍历、重复派发沿原 inbox 查重重发该证明，不再次调用模型。单有错误文案不构成证明。
- 若 `runTurn` 已尝试，即使下层返回同名错误也归入原 `TURN_ACCEPTANCE_UNKNOWN`，不能自动补全重跑。完成后 thread 不匹配同样不产生“未执行”证明。
- API 认证该执行方及原派发后，在原业务锁序（授权任务→会话→turn→outbox）下，仅允许 RECEIVED/QUEUED/DISPATCHED、无 final、无 delta 的已固定 DELTA 重准备。旧 outbox 标为 DEAD 并保留原 payload；旧 snapshot 内容及摘要不改。新 dispatch/event/snapshot 使用新身份，同一 USER/request/turn 保留。新 BOOTSTRAP 通过 `replacesDispatchId` 指向已拒绝派发；原 taskGrant/任务/材料/动作事实保持绑定，不升级成当前更宽授权。
- 一次事务完成旧派发退役、新 snapshot/outbox 插入、turn 当前指针更新、既有 resync 事件和提交后唤醒。重复拒绝只确认原处理，不再创建候选；BOOTSTRAP 本身不能再次走此分支。旧发送线程的 markDispatch 现在携带具体 dispatchId，不能改新派发状态。
- `findTurnDispatch` 和旧按 turn 锁 DISPATCH 的入口只定位 turn 当前 dispatch；普通旧 final/工具入口仍拒绝旧派发。重复拒绝有单独的已退役只读认证范围，不复用成旧 final 权限。通用 outbox 更新改按 eventId 精确更新，避免同 turn 多 attempt/version 相等时误改旧记录。
- Runtime 收到新 BOOTSTRAP 后只将本地可证实未执行且同 request/turn 的旧恢复项按既有 CANCELLED/归档机制退役；不同主机无旧项可正常执行，旧项若有执行证据则不执行替代请求。去重摘要仍保留拒绝证明；最终确认查找排除这种已证实未执行项，避免一个 turn 的旧拒绝记录制造 final ACK 歧义。不存在通用历史缓存/去重层。
- 初次准备保留 `contextInputRefs` **仅在内部 outbox**，便于未执行补全时沿用已授权引用；`hostedWire` 删除该字段及内部 PENDING 标记，不发送给 Runtime/模型。原文“准备后从 outbox 删除 refs”以此更新。

#### 必须显式迁移的两个索引

新 attempt 必须保留旧不可变输入，而旧唯一键强制每个 request-target 只有一个 snapshot、每个 turn-event 只有一个 outbox，不能靠复用旧 ID 绕过。因此：

- snapshot 的 request-target 唯一键改为普通检索索引；业务 request、turn 及最终结果幂等约束不变。
- outbox 唯一键改为 `(turn_id,dispatch_id,event_type)`；event 主键仍唯一。
- 候选 SQL：`chat/jia-chat-mapper/src/main/resources/db/chat-context-attempt-migration.sql`。它不由应用启动加载；没有执行生产 DDL。初始化器校验旧冲突唯一键已被受控迁移移除，不自动删除索引。存在多个 attempt 后不能直接回加旧唯一键或删除历史来强行回退。
- 这不是新建兜底表；正常消息不插入第二份 attempt，只有明确未执行且确需 bootstrap 的异常分支才增加 snapshot/outbox。尚无实际压力/耗时收益数据。

验证：Runtime 定向23项、native绑定/未知执行3项通过；Mapper隔离编译成功（原注解依赖6条警告仍在），实际MyBatis渲染+只读合成CTE的当前派发选择7项通过（不验证锁/事务）。新增API入口4条件、授权边界、真实事务新旧不变/重复拒绝/旧回调/回滚测试尚未Flow执行。Web原resync事件带新dispatch/snapshot/stateVersion且触发权威读取；本轮未改Web、未称UI验收通过。

## 2026-11-05 接续：原生 CHAT 准入生产者（源码候选，未发布）

- 非内置 CHAT 的新请求保存当前 USER、授权后的 inputRefs、已冻结 task/material/action/review/grant 事实；不在准入读取历史正文。以既有 `BuiltinHallAgentSupport` 路由判定区分内置执行，不按 Provider/技能名判断。内置 CHAT 与 INSPECT 仍在准入构造历史，混合目标仅原生目标使用草稿。
- 初始 snapshot facts 和内部 outbox 均有 `contextPreparation=PENDING`。这不是新增状态表；原 turn 仍 RECEIVED。既有 claim 事务准备上下文时移除两个标记、写 contextDelivery、更新原 snapshot/turn/outbox 摘要；初次 snapshotId 保持不变。模型侧不接收标记或内部 refs。
- 显式 message ref 准入只查最近200条的 ID，不读取正文/metadata。新 `ChatMessageDao.findOwnedMessageIdsWithLimit` 的 SQL 复用原带 live conversation 的精确范围条件、create_time/id 排序与 LIMIT；仅投影 `m.id`。不放宽旧引用窗口，不以原生线程持有历史作为读取授权。无 message ref 的新请求和幂等重试均不为引用校验查消息；内置/INSPECT 有引用时会独立读取必要的历史正文。
- 必要边界：草稿尚未执行，没有有效原生 checkpoint。提前 final/preview 不能把它冒充已执行上下文；既有 callback snapshot 查询识别 PENDING 即拒绝，不增加查询。final 复用该查询返回的 snapshot，去掉此前同一快照的第二次读取。wire 组装也不允许把未准备草稿送给 Runtime。
- 排队期间会话删除/代次变化：准备阶段不读历史，保留既有 relay 的失效处理；wire 在现有 live-generation fence 内创建，避免未准备草稿先因组包错误而一直重试。存储异常仍抛出，不伪装会话失效。
- r8 队列/回滚/补全测试改用真实准入产物，删除手工 UPDATE 注入 PENDING 的夹具；新增草稿拒绝 final、失效代次及 ID 窗口场景。普通新请求 final 使用 claim 后的 hash/receipt，不能继续使用准入时的草稿 hash。历史无 checkpoint 用例明确模拟已完成旧记录；mmd 修订夹具接入真实 prepare。
- 已完成的轻量诊断：22个候选 Java 源文件仅 JDK parse 无语法错误（不做类型分析、注解处理、应用编译）；ID-only SQL 9个只读合成 CTE 场景通过，未读取/修改生产业务行；diff --check通过。上述新增/调整的 Java 应用和事务测试尚未在 Flow 执行，不能作为正式验收或性能实测。证据见根 handoff。
