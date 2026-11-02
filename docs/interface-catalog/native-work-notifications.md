# Runtime work notifications v1 (native conversation and workspace file)

2026-11-02 candidate; interface standard baseline API 2723bdc37576eed2cb490c6b240638d1159d8445. This document describes the candidate, not a deployed contract. Responsibility: RB-1 API owns durable work/authorization; Runtime owns local recovery/execution; Web unchanged.

- Runtime offers `nativeWorkNotifications: 1` in `agent.register`. Only a correlated successful `agent_registered` ACK containing exactly `1` enables event-driven discovery. API acknowledges only when conversation execution is enabled. Old API or missing acknowledgement retains existing 3s discovery. Old Runtime is not subscribed.
- `runtime.work.available` uses the standard protocolVersion=1 envelope. `data`: notificationVersion=1, lane=NATIVE_CONVERSATION, tenantId, clientId, agentId, runtimeInstallationId, hostId, runtimeInstanceId, sessionGeneration (decimal string). Exact shared synthetic fixture: `chat/jia-chat-service/src/mmdRegressionTest/resources/contracts/native-work-hint-v1.json`, mirrored into Runtime `conf/cyf-agent-runtime-v1/test/fixtures/`.
- Hint is lossy and coalescible, never command, authorization, completion or durable ACK. No task contents or skill package. Consumer requires confirmed current session and complete matching proof, then invokes existing authoritative HTTP discovery/claim. Additional harmless fields are ignored; unknown lane/version does not execute anything.
- Producer registers its hint only after v5 work and its paired skill row are inserted in the existing transaction. Rollback sends nothing. Post-commit listener queues locally, never performs socket I/O in the transaction. Listener failure must not fail a committed create.
- One 30s compensation scan per WebSocket node discovers queued v5 targets among that node's local subscriptions. No subscriptions means no SQL; no queued work means no hint/Runtime inbox request. SQL partitions before selection; send checks current persistent session fence. Cross-node enqueue or lost hint can wait 30s plus processing/network; no immediate global-delivery guarantee. Database outage never authorizes execution; recovery resumes on availability.
- Runtime performs startup/reconnect discovery. In-flight hints become one trailing discovery; full pages continue while making progress. Local unresolved records/UNKNOWN due times remain recoverable without inbox requests. No idle native discovery timer after negotiation. Workspace-file and heartbeats are unchanged.

## Compensation query cost / installation

Existing owner-state index does not bound a target-based discovery scan. New explicit migration `db/agent-personal-workspace-native-work-index-v1.sql` adds a nonunique covering index over partition, target and queued predicates; does not add CHECK, change facts or claim ownership. It is **not startup DDL** or a correctness admission gate. Fresh catalogs apply it after native-v5; upgrades apply once through authorized snapshot-backed migration, inspect existing index first, and record exact schema/source. No production migration performed by this task. Rollback can leave this additive index; dropping it requires separate maintenance, not state rollback.

Owned MySQL fixture tests real mapper SQL/record mapping, case-exact partition, and EXPLAIN before/after 10,000 synthetic terminal rows. Those results do not establish production latency or query-plan stability at every subscription count. Before release, inspect installed index and representative multi-target plan under the target database statistics; no claim of already improved production CPU.

## Regression responsibility

- `NativeConversationWorkDiscoveryTest`: transaction hint timing, rollback, local partition restriction.
- `NativeConversationWorkDiscoveryMySqlTest`: actual SQL projection, isolation and covering index plan.
- `SkillExecutionServiceTest`: actual v5 producer registration ordering after paired inserts.
- `AgentWebSocketNativeBountyExecutionTest`: current negotiated session only, exact shared wire fixture, never command dispatch.
- `ChatBountyBootstrapRelayRecoveryTest.NativeWorkNotifications`: burst coalescing, trailing wake, empty/no-local subscriptions, missed/remote commit recovery and lifecycle.
- Runtime `native-conversation-wakeup`, `skill-conversation`, `runtime-host`, native poller tests: scheduler, local uncertainty, negotiation/session rejection, old server fallback and payload import closure.

## Workspace-file discovery prerequisite (2026-11-02)

Code review baseline API `604e8d15a65144183adf3839d0fa5569ad8a4f70` found file pickup's
`listQueuedByTarget` selected a mixed-lane page before service-side exclusion of CONVERSATION.
Sixteen older conversation rows could therefore hide all file commands indefinitely. File-lane
membership (`PRIVATE`, `TASK`, historical NULL-as-PRIVATE) now applies in the existing mapper
before ordering/LIMIT. Unknown modes are not interpreted as file commands. This is selection,
not authorization: runtime scope, task lease, business state and command validation stay in their
existing responsibility layers. The only production caller is `runtimeQueuedCommands`; native
conversation discovery uses its separate query and is unchanged. No HTTP/wire/DDL change.

`WorkspaceFileQueueMySqlTest` reproduces the old empty-after-filter page on real owned MySQL,
then checks mixed lanes, historical NULL mode, unsupported/terminal rows, exact partition,
ordering/limit and subsequent pickup after completion. Verification status is recorded in the
root JUYITING-POLLING-20261102 handoff, not implied by this documentation.

This prerequisite does **not** enable workspace-file notifications or remove its 3s poll.
The processor's durable inbox resume/UNKNOWN handling stays untouched. In particular file
pickup is not a claim: already-admitted rows can remain QUEUED until execution progresses.
A future notification consumer must not spin on a full page of duplicates, and must negotiate
file-lane support independently of `nativeWorkNotifications:1`. Expired TASK lease candidates
are still service-filtered after SQL selection; that separate readiness/pagination dependency
must be resolved before declaring the file lane's event-discovery closure complete.

## Workspace-file event discovery candidate (2026-11-02)

This extension supersedes the prerequisite section's pending implementation notes; it is
not deployment evidence. Interface baseline API `189da86d622a37254f31fd5828bf96b7bc01bbbe`.

- Independent offer/ACK `workspaceFileWorkNotifications:1`. Only the current correlated
  successful registration enables file event discovery; `nativeWorkNotifications:1` alone
  never does. File support does not require enabling conversation execution. Old peers keep
  file 3-second discovery. WS subscriptions activate after receipt delivery and retire on
  disconnect/re-registration; hints still check the current persistent session fence.
- Shared `runtime.work.available` envelope, version 1, lane `WORKSPACE_FILE`; all proof fields
  unchanged. Exact fixture `contracts/workspace-file-work-hint-v1.json` is mirrored in Runtime.
  Unknown/unnegotiated lanes, wrong version or session proof never enter command ingress.
- PRIVATE/TASK create registers the target-only event after execution and all input rows;
  actual send is after commit on the existing worker. The historical `NativeConversationWork*`
  classes now carry an explicit lane; no duplicate scheduler or second broker. One 30-second
  compensation pass queries both subscribed lanes together in the process partition. Empty
  subscriptions skip SQL. Hints are not authorization or a durable command queue.
- The authoritative file GET still returns `{items:[...]}` (up to 16), unchanged wire. Inside
  that transaction the existing service checks leases and command eligibility; if a candidate
  page cannot fill the response, it continues by `(created_at, binary execution_id)` keyset
  until full or exhausted. No offsets, row deletion, fabricated lease or duplicate authorization
  logic in SQL/Runtime. Only one candidate page plus the bounded response is retained.
- Runtime reuses the existing coalescing one-shot scheduler. Startup/reconnect always reads;
  idle negotiated file discovery has no periodic timer. HTTP/network failures retain 3s retry.
  Local processor resume/ACK/UNKNOWN recovery remains independent and unchanged. A non-empty
  page is not a claim: duplicates may remain QUEUED during execution. Observe the existing
  drain promise once, wake on completion; only a full page with newly admitted commands and
  already-finished drain can immediately continue. Duplicate-only pages do not spin. In-flight
  hints coalesce; stopped/old-session callbacks cannot wake a replacement loop.
- Compensation intentionally reads candidate state, not business lease authorization. Stale or
  non-dispatchable QUEUED candidates can still cause a 30s hint/read, and a large invalid backlog
  can require multiple bounded pages per GET. This change fixes starvation, not stale-work
  lifecycle cleanup or a bound on total scan duration. Cross-node/lost-hint delay remains about
  30s plus processing. No production CPU/latency savings asserted.
- No new migration in this extension: compensation uses the previous explicit target index;
  its production installation/EXPLAIN still require authorized snapshot-backed verification.
  No startup DDL, CHECK or mandatory size/time cutoff.

Added regressions cover real SQL keyset/lanes, business eligibility traversal, create hint
ordering, transaction publication, shared notifier, WS independent negotiation/fence, mirrored
wire, old-server fallback, full/duplicate pages, real persistent inbox/dedupe, in-flight ownership
and reconnect. Heavy Java results and exact integrated commits belong in the root handoff.

## Unified polling follow-up (2026-11-03, source candidate; not deployment evidence)

Interface baseline: API192a590b2c8b690337818606a6180fca6f99a600,
`docs/interface-standard.md`. Scope is chat outbox scheduling and archive service
availability, not a second queue or a replacement authorization layer.

- `agent.registered.data.archiveJobDiscovery` is a server-declared integer 0/1,
  derived from the same `agent.position-skills.enabled` property as
  ArchiveMaintenanceRuntimeController. A client cannot override it. This property
  does NOT disable the unconditional PositionSkillRuntimeController.
- A correlated, authenticated successful registration with 0 suppresses archive
  job discovery only. 1 enables the existing path; absent field preserves older
  API behavior. Re-registration replaces the capability. Invalid values reject
  registration. A prior in-flight positions query cannot start archive discovery
  after registration/session replacement. No permanent 404 negative cache.
- Shared complete envelope fixture:
  `chat/jia-chat-service/src/mmdRegressionTest/resources/contracts/archive-discovery-registration-v1.json`,
  mirrored in Runtime `conf/cyf-agent-runtime-v1/test/fixtures/`.
- ChatDeliberationOutboxWakeup is an in-process after-commit scheduling hint.
  Dispatch/final/cancel/action/revision/submission producers and retry/ACK
  settlements publish it; rollback publishes nothing. Claims still use the
  authoritative SQL transaction, fencing and existing ACK/lease rules.
- Relay uses one coalesced one-shot discovery timer, existing four delivery slots,
  and a 5-second compensation ceiling (not an application request deadline).
  Successful delivery/slot release wakes the next batch; future retry/ACK/lease
  deadlines are read in the configured tenant/client partition, including those
  crossed during discovery. Restart/lost or cross-process hints recover from DB.
  Idle scans do two bounded-result queries per pass; this reduces frequency,
  not the number of rows a query could examine. Existing status index is reused;
  no DDL. Heartbeats and business cancellation checks remain necessary timers.
- Stop invalidates timer ownership, prevents executor resurrection and releases
  slots for never-started queued deliveries; durable claims recover by lease.
  Notification failure cannot roll back a committed business transaction.

Runtime1.3.37/bridge1.1.27 also reuses existing 15s-to-5min jittered recovery
scheduling for command-local transport/unconfirmed-response/environment errors.
This is no timeout, terminal state or grant to replay UNKNOWN. Persisted state is
unchanged; retained native mapping mismatch has an explicit environment-changed
code, while unsafe marker structure retains its original failure. Rejected HTTP
bodies are canceled; numeric HTTP status is reported without payload/credentials.
Tests cover commit/rollback, coalescing, deadline crossing, restart, failure,
real partitioned SQL, capability negotiation and no second model invocation.
Historical recovery records and one unreproduced wire rejection remain separate
operational findings; backoff does not mean those executions are repaired.
