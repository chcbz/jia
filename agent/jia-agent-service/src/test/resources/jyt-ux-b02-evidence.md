# JYT-UX B02 first candidate: independent reads + TASK_CREATE

Source candidate only. The Owner did not run Java, Gradle, MySQL, containers, Provider, Flow,
production builds or deployments. Test sources are not PASS evidence.

## Frozen first-slice read contract

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
  targetAgent:{agentId}?,nextAction,allowedActions,updatedAt}`. Null title stays null (legacy runs).
  No prompts, URLs, funding assertions or outputs are fabricated.
- Sources/actions: DRAFT/EDIT_DRAFT (+DISCARD_DRAFT), PRIVATE_CASE/OPEN_CASE,
  LEGACY_EXECUTION/OPEN_EXECUTION, TASK/OPEN_TASK. These are hints, not mutation authority.
- Existing raw private and task states stay separate. Needs-action evidence in this slice:
  EDITING drafts, FAILED private executions, failed task roots; never QUEUED/unread inference.
  Private needsAction remains partial/VIEWED_RESULT_NOT_TRACKED; task needsAction remains
  partial/TASK_REVIEW_NOT_PROJECTED. Actual failure/corruption: error/HALL_SOURCE_UNAVAILABLE.
  An empty error partition is not an empty success. Unknown count stays null.
- JWT scope, private/no-store (including pre-security errors), milliseconds.
  `asOf`/observedAt are sampling times, not a transaction snapshot/state-change timestamp.
- Archive returns 422 HALL_READ_VIEW_UNAVAILABLE. Private viewed-result/archive marks and
  real formal review projection are NOT delivered by this commit; B02 is not fully complete.

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
- No new production tables, migration or DML. Existing nullable submitted_execution_id supports
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
external broker/event transports are mocked. Private read fixture is intentionally projection-only,
not execution/output-content integration proof. No credentials are written by fixture code.

From repository root, the ordered paths and hash algorithm are reproducible:

```sh
while IFS= read -r path; do sha256sum "$path"; done \
  < agent/jia-agent-service/src/test/resources/jyt-ux-b02-fixture-inputs.txt | sha256sum
```

This hashes the UTF-8 sha256sum lines (lowercase hex + two spaces + relative path + LF),
not raw concatenated file bytes. Cache key also includes exact Git tree and selector.
