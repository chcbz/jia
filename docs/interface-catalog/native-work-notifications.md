# Native CONVERSATION work notifications v1

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
