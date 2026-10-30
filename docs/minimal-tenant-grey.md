# Minimal tenant grey process

Scope checked 2026-10-29 against integration of API develop d24268d3be and grey 5f59833cec.
This is a minimal local tenant-1 validation profile, not a production switch or a complete multi-tenant rollout.
Public interface guidance is `/home/isp/wsps/cyf/api/docs/interface-standard.md` v1.4 (workspace rule;
committed older document baselines do not override it). API owns authorization and completion facts (RB-02/RB-07).
No Runtime execution-lane deletion or replay change is introduced here.

## Compatibility and isolation

- Normal deployments leave fixed-tenant disabled. Existing tenant-zero `/user/my` retains its UserEntity fields and masked password;
  the nonlegacy tenant receives only the exact authenticated own-user identity projection.
- Existing exact OAuth clients with absent historical tenant metadata retain canonical legacy tenant issuance.
  A missing client is still rejected; explicitly foreign tenants, padded mappings and missing grey mappings are rejected.
  This preserves the former single-tenant issuance behavior without admitting a tenant-1 client to production.
- Normal chat outbox discovery explicitly selects the configured legacy tenant, across its legitimate clients.
  Grey discovery selects the exact configured tenant AND client before LIMIT/claim. Historical DAO discovery means tenant zero,
  not every tenant. Existing lease ownership, fence, transaction and retry semantics are unchanged.
- A production worker running older all-tenant discovery is not made safe by merely starting the new grey process.
  Before using a shared production database, all consuming normal workers must run the isolation fix, or use a separate test database.
  Merging this source alone is not deployment or permission to create grey data in production.
- The profile disables scheduled tasks, Rabbit publishing/consumption and startup schema mutation. Its route allowlist admits
  identity, Agent roster/persona, Runtime enrollment/session/ACK and minimal CHAT only. Tasks, skill execution, personal workspace,
  economy and formal delivery are not validated by this profile. Do not broaden routes without implementing their tenant boundaries.
- Use a separate local API issuer/port, Redis, Runtime identity/state and file roots. Never copy the production Runtime identity/state
  or retarget an existing production process. Database schema and exact tenant/client enrollment must be prepared by authorized operations.

## Necessary checks and failure scope

| Risk / condition | Required behavior | Regression |
|---|---|---|
| Shared DB has tenant-1 READY/RETRY/AWAITING_ACK or expired CLAIMED chat rows | Normal discovery must not claim/change their leases or consume normal capacity; converse holds for grey | `:chat:jia-chat-service:r8TenantPrivateChat`: R8TenantPrivateChatTest and TenantOutboxIsolationMySqlTest (private real MySQL) |
| Legacy registered client has null/empty tenant metadata | Preserve legacy token scope for login/refresh/machine; never apply fallback to grey or nonexistent clients | FixedTenantAdmissionPolicyTest; AuthorizationServerConfigTest |
| Existing browser uses optional own-user fields | Preserve normal `/user/my` shape and password masking; keep grey minimal projection | UserLegacyIdentityCompatibilityTest; UserOwnIdentityRealMapperTest |

Tests/build run through existing Flow5260799 and orchestrator; exact commit/tree and CI-only evidence are recorded in
`/home/isp/wsps/cyf/docs/implementation/handoffs/MINIMAL-TENANT-GRAY-MERGE-CHECK-20261028.md`.
No production data migration, deployment, new approval system or full-project dual-protocol mechanism is authorized by this document.

## Full-gray extension candidate — 2026-10-29

Base: develop `f932d38ccd8701ac06d5167a07f420d4cf1e3c12` (tree
`78ae168f19d027d50cc97ba008ef43cbd491c1f9`). This section describes source changes
under FULL-TENANT-GRAY-20261029, **not deployed/verified behavior**. The older minimal
scope above remains the delivered baseline until new Flow and installation evidence exists.

The first extension is private workspace **file CRUD/read only**. HTTP maps admitted
JWT scope to a framework-free service scope; the JWT tenant is an assertion of process
configuration, not an OAuth-client/user database attribute or caller-selected business field.
DAO predicates, operation uniqueness and the existing storage scope hash include tenant,
client and owner. Legacy user tokens continue to resolve to tenant zero; malformed or
conflicting scope claims fail before service access. Runtime output metadata additionally
requires the version tenant/client to match its file and write scope.

The gray profile retains opt-in storage to avoid breaking existing minimal launchers:
`CYF_LOCAL_WORKSPACE_ENABLED=true` requires a positive
`CYF_LOCAL_WORKSPACE_MAX_CONTENT_BYTES` using the deployment's chosen file-size policy.
Objects are under `${CYF_LOCAL_STATE_ROOT}/personal-workspace` (default local state root
`/tmp/cyf-gray-minimal`); use a separate durable local root when persistence is needed.
No production storage/configuration or startup DDL is changed. With storage disabled,
the added route support does not imply usable uploads. Production default configuration
is unchanged. Task execution, file links, Runtime grants, paid/external providers and
formal delivery remain denied pending their end-to-end isolation work.

| Concrete risk | Regression source (execution pending) |
|---|---|
| Same identity/idempotency key shares rows or bytes across tenant0/1 | PersonalWorkspaceHttpPersistenceIntegrationTest (real DAO/transactions with H2, not MySQL proof); FileSystemPersonalWorkspaceStorageTest |
| Forged upload tenant input or conflicting JWT claims changes scope | PersonalWorkspaceControllerPreviewTest; existing EsSecurityContextFilterTest |
| Runtime output version points to another tenant/client | PersonalWorkspaceWriteServiceTest |
| Route expansion inadvertently exposes execution or other tenants | MinimalGreyTenantRouteFilterTest |

Workspace tests reuse `:agent:jia-agent-service:v1131Acceptance`; no new runner is
introduced. Include resource filter regressions, startup safety and `validateLayering`
in the authoritative Flow verification. No production acceptance claim follows from
source inspection or synthetic H2 regression.

### File-link isolation candidate (2026-10-29; based on develop d3aa82d0ea)

Task/conversation link controllers now carry the admitted tenant assertion through
framework-free scopes. Services/DAOs retain exact tenant/client/owner predicates,
transactional reservations, OUTPUT restrictions, conditional detach and cursor binding.
Chat-owned conversation authorization uses the explicit four-argument tenant DAO;
it still rejects deleted, foreign, malformed and task-thread conversations.
**Link routes remain closed in minimal-grey pending complete chain verification.**

Schema audit: the canonical workspace file/version/operation tables already have
scope-aware indexes and no tenant-zero CHECK. The four link/operation tables did
have tenant-zero CHECKs. Fresh DDL removes only those four obsolete constraints.
Existing databases need explicit maintenance script
`db/agent-personal-workspace-tenant-links-20261029.sql`; merely rerunning CREATE TABLE
or deploying this JAR does not migrate them. This script is not registered with
normal/gray startup. Before authorized execution, fix the database target, capture
SHOW CREATE TABLE and backups, and check all four tables are present. MySQL DDL
commits independently; the script tolerates retry after partial execution. Read back
all four removals and preserved business CHECKs/indexes. Application rollback leaves
this additive schema in place: do not reinstate tenant=0 restrictions over nonzero
rows, delete gray data, or restore old data snapshots over new production facts.
No production DDL or gray environment acceptance has occurred for this candidate.

Synthetic regressions (authored, not yet execution evidence): controller malformed/
conflicting JWT and query override denial; chat exact-tenant ACL; existing
`TaskLinkedMaterialReaderMySqlTest` now also installs canonical MySQL DDL, exercises
fresh and twice-retried legacy migration, preserves production rows, verifies
same-key independent reservations, reciprocal scoped reads, conditional detach and
business CHECKs, and real service transaction rollback on foreign-file denial.
Conversation ACL in that SQL service test is a mocked port; its actual implementation
has separate focused tests, not an end-to-end CHAT/browser claim.

### 2026-10-29 任务只读与 bootstrap 后台隔离候选

基线 `77de2687eac0aa53f7fc7c48a5974346436be620` 后续候选；不代表已上线或全业务灰度完成。
任务 workspace/SSE 从已准入身份解析租户，保留旧用户 token 默认0、owner/client/member ACL；query 不能覆盖租户。事件 replay/broker 按完整 scope 分区，事件写入仍只支持0，未提前开放任务创建、native执行及资金链路。
Bootstrap discovery 在 LIMIT/锁定前按进程固定租户（灰度另固定 client）筛选；显式 claim/reconcile 也校验进程准入。生产仍在0内发现合法 client；需求快照仅开放隔离只读，重确认写入未开放。`minimal-grey` 显式关闭独立线程的 `chat.bounty-bootstrap` relay，不能依赖全局 scheduling 开关。

已有表需要独立授权执行 `db/agent-task-bootstrap-tenant-20261029.sql` 才能存储灰度事实；启动不会执行此维护脚本。执行前固定目标数据库，备份两张表及 SHOW CREATE TABLE；脚本不迁移历史数据，DDL自动提交，可重复执行。新旧生产 CHECK 均应通过启动校验，保留 owner/内容/状态/租约/唯一索引保护，不能要求生产先迁移才能升级应用。回退程序不应将约束恢复为 tenant0 并排斥已有灰度行；保留数据及兼容 schema。

回归入口：现有 `mmdU1BootstrapOutbox`（含 Flow 自有 MySQL fixture、新旧 schema 校验和双租户 claim/reconcile）；workspace/SSE/broker/replay、DAO精确过滤及 repeatable-read 相关测试。需记录实际 Flow 结果后才能宣称通过；不使用本机或生产库作为 fixture。

### 2026-10-30 当前需求读取与执行入口作用域（候选，基线69c5f02499）

- 当前需求读取从已验证JWT解析租户，经精确tenant/client/owner/task根锁再读不可变快照；保留根行复核、摘要校验、读一致性和原生产0行为。根锁是作用域原语，不是写入授权；创建/预留、重确认、授权/执行写入的原有0限制未开放。
- 浏览器个人执行入口不再把声明的灰度租户改写成0，冲突/非法租户声明403；请求查询不能覆盖身份。执行服务仍拒绝未适配租户，不代表灰度执行已支持。Runtime文件/会话入口原已使用Runtime认证租户，其下游执行服务仍有0限制。
- minimal-grey路由与配置不变，无生产DDL/安装；正式回归及合入证据见根任务handoff。

### 2026-10-30 普通建任务授权上下文（候选，尚未上线）

基线 develop `85162da0d548a87b392836132e8fcdb848304816`。普通建任务的两个 HTTP 入口和 Chat 工具适配器显式传入 `AgentService.TaskOwnerScope`；创建幂等服务不再读取 Spring Security/EsContext，任务根、计划的 owner/client 以及回执成员查询使用已传入的身份，而非线程默认值。HTTP 身份校验保留在边界，租户不是模型/请求可指定的业务字段；缺失身份不得回退为 juyiting。

此步是授权上下文解耦，**不表示 tenant1 已支持建任务**。创建服务和幂等服务仍在任何存储访问前拒绝非0租户；任务根预约/改键、快照捕获、事件写入、操作幂等表及 task_plan/task_item 的持久隔离须一起完成后再放开。未修改数据库/灰度路由/生产进程，不包含资金流程变更或灰度环境验收。正式回归、合入及交付以根 handoff 的真实 Flow 记录为准。
