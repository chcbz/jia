# 待承办事项当前需求编辑（2026-11-04）

公共接口遵循 `docs/interface-standard.md`（本次核对文件头1.2、blob 806e3b5e918bff6cb6d54abd2ceaee21f519f92f；前版文档写1.4不准确）。本功能不授予承办/资金/执行权限。

## 当前态、业务快照、审计分离
- 每事项一条 `agent_task_current_requirement`，标题、描述原地更新；资料沿用固定版本link。`revision`为并发/输入标识，不表示每次新增历史记录。详情/推荐/current读取当前表，不扫描历史。
- 普通编辑不调用reconfirm、不追加完整快照。仅真实内容或资料集合变化更新当前revision及taskVersion；同值保存保持两版本，不更新摘要或资料，不重新推荐。
- 创建保留一条CREATE业务快照，服务现有原子创建回执；承办授权事务将当前revision冻结到原快照表，与grant输入固定资料同事务，重试不重复。执行精确revision读取、已有快照与摘要不改写。显式历史owner reconfirm恢复路径保留，不是普通编辑入口。
- 审计系统不是本次交付内容；业务读取/保存/推荐不依赖审计。新增最小编辑操作表仅保存key、请求/结果摘要及结果版本，不保存全文历史，用于幂等与丢失响应核对。

## HTTP（保持现有路由，不为命名单独扩建接口）
- GET `/agent/tasks/{taskId}/requirements/edit`：owner读取当前可编辑需求，返回 `taskId,taskVersion,requirementRevision,title,description,attachments:[{fileId,version}]`。
- PUT 同路由：Q.data为 `expectedTaskVersion,expectedRequirementRevision,title,description,attachments`，`Idempotency-Key`必填（1–80位字母数字下划线连字符，非CREATE）；返回相同View。
- 两个版本为Long十进制字符串；资料版本正int number。沿用标题200、描述20000、资料32/fileId100输入限制，不新增性能阈值。
- 有变化保存两版本各+1；无变化返回原版本。Web收到权威回执后有变化才清空旧承办选择并调用原recommend，失败不回滚保存。
- GET携带原`Idempotency-Key`只核对该已提交操作：不存在404；当前状态/版本/资料已变409；不能用文本相同推断另一个操作成功。查询无写入，不重放。PUT同key同输入只恢复仍匹配当前事实的结果，同key异参或后续变更409，不覆盖新事实。此接口未提供历史操作详情展示。

## 责任及必要校验
API根锁统一owner/待承办权限、并发与事务；普通open且无指派/开工/funding事实才可编辑。资料ACL及固定版本由link服务执行；更新短摘要不调用会重建计划条目的TaskService.update。保留输入Unicode/摘要完整性。请求错误400、非owner404、不可编辑/冲突409、存储故障503；no-store。

## 数据切换
先按固定候选执行受控备份及停写迁移：新建current/edit-operation两表（无CHECK），从既有完整快照的最高revision精确复制当前输入及原摘要；不从TaskPlan补全文，不覆盖已有current，不修改历史。资源见mapper `db/agent-task-current-requirement-v1.sql`、`agent-task-requirement-edit-operation-v1.sql`、`agent-task-current-requirement-backfill-v1.sql`。正常应用启动不执行DDL/回填。新旧API不可同时写；回退旧API前须把未冻结current按原摘要补成业务兼容快照，不能直接回退后读取陈旧历史。

回归：CurrentRequirementTransaction、RequirementEditService/Controller、SnapshotService、ExecutionGrant、CreationOperation及推荐消费者；新表迁移尚须真实MySQL及生产独立验证。源码/Flow/安装状态以根handoff为准。
