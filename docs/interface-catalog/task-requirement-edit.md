# 待承办事项需求编辑（2026-11-04）

遵循公共接口规范 1.4（`docs/interface-standard.md`，本任务基线 blob `806e3b5e918bff6cb6d54abd2ceaee21f519f92f`）。本接口不授予承办/资金/执行权限。

- `GET /agent/tasks/{taskId}/requirements/edit`：JWT owner 读取可编辑的当前需求与 ACTIVE INPUT/REFERENCE 固定版本资料，返回 `taskId,taskVersion,requirementRevision,title,description,attachments:[{fileId,version}]`。
- `PUT` 同路由，Q.data 必填 `expectedTaskVersion,expectedRequirementRevision,title,description,attachments`；`Idempotency-Key` 必填，使用一次编辑的稳定标识（1–80位字母/数字/下划线/连字符，不能为 CREATE）。返回相同 View 的 R.data。
- 两个 version/revision 为 Java Long 十进制字符串；资料 version 为正 int number。标题沿用创建范围200字符，描述20000字符，资料最多32份，fileId最长100；均来自已有创建/选择器/存储规格，不新增性能门槛。标题、描述不可全空白；成功保存不自动指派、不执行、不扣费。
- owner 范围在既有 root-lock 原语中核验；仅普通 open、无已指派/已开工事实、无 funding 事实可编辑。任务根锁与指派/取消串行，当前 taskVersion 和 requirementRevision 必须同时匹配；非owner404、不支持状态409、版本冲突409，输入错误400，存储故障503，均 no-store。
- 同事务复用 append-only requirement.reconfirm（历史原文不改写）、资料关联 create/detach（OUTPUT不碰、执行历史不碰）、旧 TaskPlan 短摘要和 taskVersion+1；资料ACL与固定版本存在性由既有 link 服务负责。中途失败全部回滚，不能出现新原文配旧资料。无需新增表/字段/CHECK或生产迁移。
- 丢失响应使用同 key/同完整输入恢复。只有当前根/需求恰为原预期版本+1、文本与资料集合相同，且原 confirmationId 重放核对成功时返回成功；后续状态/需求已更新则409，不重放旧编辑或回退新事实。同key异参不覆盖。读取确认结果不隐式发起写入。
- 真实完整需求作为详情及推荐输入来源；列表继续使用现有短摘要。保存成功后Web更新详情/列表并清空旧承办选择，以新版本调用既有 recommend。推荐失败保留保存事实并提供手动重试；旧推荐按版本/身份取消，不覆盖新需求。
- 必要校验及风险：owner/root锁防越权与指派竞态，版本防丢失更新，confirmation防重复改写，资料link层防越权文件，事务防文字/资料分裂，原始Unicode/摘要校验防不可恢复证据损坏。回归：`AgentTaskRequirementEditServiceImplTest`（真实Spring/H2事务）、`AgentTaskRequirementEditControllerTest`（Q/R/字符串版本/身份/409），并保留 snapshot/link/creation/AgentService/recommendation 既有测试。

源码实现/Flow/安装/在线验收分别见根 handoff；本规格不是上线证明。
