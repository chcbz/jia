# JYT-UX B02 source candidates: scoped reads, TASK_CREATE and private marks

Source candidate only. The Owner did not run Java, Gradle, MySQL, containers, Provider, Flow,
production builds or deployments. Test sources are not PASS evidence.

## Compatible read contract after private-mark follow-up

- `GET /agent/hall/overview`: no query; `schemaVersion=1`, `sections={recent,needsAction}`,
  `sourceStatus={recent:{private,task,draft},needsAction:{private,task,draft}}`, `asOf`.
- `GET /agent/hall/items`: optional `kind=all|private|task|draft` (all),
  `view=recent|needsAction|archive` (recent), name-only `q` (empty), `cursor`.
  Response: `schemaVersion,kind,view,q,section,sourceStatus,asOf`.
- `Section={status,partitions:{private?,task?,draft?}}`;
  `Partition={items,status,nextCursor,count:null,errorCode}`;
  `SourceStatus={status,errorCode}`. Status: `complete|partial|error`.
  Complete means source query succeeded; end of pagination is `nextCursor:null`.
- Explicit partitions, not a combined globally sorted page/total. Continue each partition using
  its corresponding kind and same view/q. An all-kind request cannot carry a continuation cursor.
  Each page is 20 items, as in existing drafts; no maximum page count.
  Sort: updatedAt DESC, binary sourceType DESC, binary sourceId DESC.
  Cursor validates canonical encoding and scope/filter binding; it is not an ACL credential.
  Every resumed SQL query independently reapplies exact owner/client/tenant scope.
- `ItemSummary={ref:{sourceType,sourceId},title,status:{code,evidenceSource,observedAt},
  targetAgent:{agentId}?,nextAction,allowedActions,updatedAt,personalMark?,review?}`.
  Additive nullable fields only; schemaVersion and existing fields are unchanged.
  `personalMark={revision,archived,viewedResultRef:{executionId,manifestId}|null}` is private only.
  `review={code:FORMAL_DELIVERY_SUBMITTED,deliveryId,workItemId,deliveryVersion,taskVersion}`
  is formal-only; versions are decimal strings. Null title stays null (legacy runs).
  No prompts, URLs, funding assertions or outputs are fabricated.
- Sources/actions: DRAFT/EDIT_DRAFT (+DISCARD_DRAFT), PRIVATE_CASE/OPEN_CASE,
  LEGACY_EXECUTION/OPEN_EXECUTION, TASK/OPEN_TASK. These are hints, not mutation authority.
- Existing raw private and task states stay separate. Private needsAction includes FAILED and
  OUTPUT_COMMITTED whose execution differs from the last server-validated viewedResultRef.
  QUEUED is not inferred running or actionable. Private recent/needsAction exclude effective archives.
  Task needsAction projects failed/blocked/reviewing roots; it is not a full task-assignment action list.
  With `jia.agent.formal-delivery.enabled=true`, a reviewing task requires its original owner-scoped
  work item, scoped latest submitted delivery, fixed manifest binding, delivery item presence, and
  released lease. It only hints OPEN_TASK; the original formal service must revalidate decisions/files.
  With that existing feature disabled, no optional formal table is queried and task needsAction stays
  partial/FORMAL_REVIEW_UNAVAILABLE. No old data or state is repaired by this read model.
- Source failure/corruption: error/HALL_SOURCE_UNAVAILABLE, never empty success.
  Unknown count stays null. Private archive is real mark-backed pagination; task archive uses the
  original task-root archived state. Draft discard is not archive: draft archive returns a partition
  error/HALL_DRAFT_ARCHIVE_UNAVAILABLE (all/archive remains partial, not an HTTP422 blanket).
- JWT scope, private/no-store (including pre-security errors), milliseconds.
  `asOf`/observedAt are sampling times, not a transaction snapshot/state-change timestamp.

## Private mark write/read contract

- `GET/PATCH /agent/hall/items/{sourceType}/{sourceId}/mark`, no query parameters.
  Only PRIVATE_CASE and LEGACY_EXECUTION; TASK gets 422, TASK-mode executions are hidden as404.
- PATCH: Idempotency-Key, `{expectedRevision,archived,viewedResultRef?}`; required safe-integer CAS
  revision starts0, archived is a required boolean. Omitted/null viewedResultRef preserves the previous
  reference, it does not clear it. The only accepted explicit viewed ref is current
  `{executionId,manifestId}` independently checked via the unchanged B03 fixed-output reader,
  OUTPUT_COMMITTED and nonempty/allAVAILABLE outputs. Client references grant no access.
- Receipt/GET: `{ref,revision,archived,viewedResultRef,updatedAt}`. Same key+same command returns its
  immutable original receipt, including after a later source change. GET returns current effective
  archive. Same key/different item or command409; stale new-key revision412; invisible source404;
  invalid input400; unavailable manifest422; actual storage503. Codes use HALL_MARK_<Reason>.
- Archive is bound to source execution/state/update watermark. A new linked execution or source change
  clears effective archive and reappears in recent; a new OUTPUT_COMMITTED run appears in needsAction
  until viewed. Previous viewed manifest is retained, and B03 history remains readable.
- **One additive table is necessary**: draft lifecycle, case revision and immutable case_execution
  cannot store independent per-user organization/CAS receipts without altering business state.
  `hall_private_mark` is append-only; operations double as durable idempotency receipts. No backfill,
  execution/task updates, Provider calls, formal acceptance, fees, or production DDL/DML were run.
  Initializer uses existing personal-workspace-storage feature enablement, verifies required InnoDB,
  exact identity keys/collations and enforced checks, does not repair drift or introduce a version gate.
  Case and mark initializers share HallSchemaSql's quote/comment-aware splitter; actual semicolons
  remain in both resources' COMMENT strings and parser tests preserve their contents.
- Source lock order is case then execution, or legacy execution only; no draft/task locks are acquired.
  Marker reads are scope-exact and writes use unique scoped operation and source revision keys.

## TASK_CREATE boundary

Existing `POST /agent/hall/drafts/{id}/submit` and reconciliation receipt are unchanged in shape:
`ref={sourceType:TASK,sourceId:taskId}, execution:null, task={taskId,taskVersion}, submittedAt`.
Task version is a live authorized read (like execution state in old receipts), not an immutable
creation-version snapshot. Unfunded, unassigned task only; no Provider execution or money authority.

The adapter requires exact JWT/Hall scope/EsContext identity, a real writable enclosing transaction,
and actual TaskService availability. Existing AgentService.createTask retains task-root reservation,
TaskService plan/items, task event, and after-commit publications. Hall key reservation and receipt
commit share REQUIRED propagation. Absence of TaskService may not become metadata-only success.
The adapter verifies exact persisted task root and plan identity and lossless title/body round trip.

- Title <=30 and instruction <=200 UTF-16 units reflect existing `taskPlanFor` substring limits,
  not newly invented product gates. No silent truncation.
- Target agent, output MIME, inputs, sourceRef and conversation intent are unsupported for this
  creation adapter and return explicit 422 rather than silently discarded intent. Draft origin
  remains provenance only. Funding/reward fields are never synthesized.
- TASK_CREATE itself adds no production tables, migration or DML. Existing nullable submitted_execution_id supports
  TASK_CREATE; private/TASK_ACTION lifecycle requirements remain intact.

## Exact selectors / fixture recipe

Orchestrator-only selector: `:agent:jia-agent-service:jytUxB02`.
Real SQL/transaction target:
`--tests cn.jia.agent.service.impl.HallReadTaskCreateMySqlTest`.
The source set also includes B01B regression/real MySQL fixture and existing HTTP/cache tests.

MySQL opt-in reuses `JYT_UX_B01B_MYSQL_URL/USER/PASSWORD/DATABASE_PREFIX/ISOLATED_FIXTURE`.
Only acknowledged task-prefixed temporary databases `<prefix>_jyt_b02_<random>` are created/dropped.
New fixture adds no exact engine-version gate. B01B's existing 8.0.21 check remains a deployed-engine
compatibility evidence target, not a new production admission restriction; real safety checks remain.
Actual TaskService, AgentService, task/event/Hall MyBatis DAOs and Spring transaction manager are used;
external broker/event transports are mocked. Private-mark tests use real source row locks, marker SQL,
B03 manifest/file-version metadata reads, lost-response replay, concurrent same-key CAS, outer rollback,
foreign scope, old-run visibility, new case execution resurfacing and schema drift checks.
Formal fixture checks owner work-item binding, released lease, artifact mismatch and no state writes.
These are deliberately metadata/transaction fixtures, not actual Provider, file-content/download or
formal decision end-to-end proof. No credentials are written by fixture code.

The follow-up static review also corrected an accidentally un-commented event-DDL header in the
first-slice test SQL and aligned the file fixture column with the production `state` column.
Neither correction is a claim that the fixture ran. First-slice tests and follow-up remain pending
main-controller isolated execution. No frontend/real browser or all-design acceptance is claimed.

From repository root, the ordered paths and hash algorithm are reproducible:

```sh
while IFS= read -r path; do sha256sum "$path"; done \
  < agent/jia-agent-service/src/test/resources/jyt-ux-b02-fixture-inputs.txt | sha256sum
```

This hashes the UTF-8 sha256sum lines (lowercase hex + two spaces + relative path + LF),
not raw concatenated file bytes. Cache key also includes exact Git tree and selector.

## Owner's actual follow-up static checks (2026-09-22)

- `git diff --check`; lightweight lexical delimiter and project-local import existence checks on
  27 Java files (24 mark/read changes plus 3 independently committed parser-fix files).
- Verified all 16 `jytUxB02` source-set include paths exist; checked SQL resource statement/quote/
  parenthesis structure (case2, mark1, isolated fixture13); not a Java or MySQL parser execution.
- Verified case/mark initializers both call `HallSchemaSql.split`, and both resource COMMENT strings
  retain semicolons. No successful application startup is claimed.
- Ordered20-path fixture digest, cross-checked with Python hashlib and the exact shell recipe above:
  `f89bd799c28413ecf464d980556e89628bf7fffcf92c1e864e9b1c05b76cac96`.
- Work resumed at observed 2026-09-22T08:12:20+08:00; final static scan 08:31:27+08:00.
  This includes two separately handed-off urgent patches (FakeDao compile fix and quoted-DDL split).
- **Zero tests executed by this Owner in this follow-up.** Main's reported B03 attempt on the old
  candidate ran31 (22passed/9failed in MySQL setup); it does not validate these changed bytes.
  Main must bind isolated test results to the resulting exact tree, selector and fixture digest.
