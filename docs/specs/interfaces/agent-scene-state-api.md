# Agent Scene State API

## Scope and security

The scene API exposes semantic movement state for the Juyiting hall. It never exposes coordinates, paths, animation frames, credentials, chat text, raw model output, or stack traces.

- Base path: `/agent/scenes`
- Phase-one scene: `juyiting-main`
- Authentication and authorization use the existing `/agent/**` resource-server rules.
- Every operation is scoped by the authenticated tenant (`jiacn`), current `clientId`, and requested `sceneId`.
- A caller cannot select another tenant or client through request fields.
- All timestamps are Unix epoch milliseconds.

## Feature flags

Both flags default to `false` in every committed backend profile:

```properties
juyiting.scene-state.enabled=false
juyiting.scene-events.enabled=false
```

| Flag | Effect |
| --- | --- |
| `juyiting.scene-state.enabled` | Enables semantic scene writes from existing Agent business operations. When disabled, task/dialogue behavior and legacy `/agent/map` responses are unchanged and no business scene write is attempted. |
| `juyiting.scene-events.enabled` | Enables the SSE endpoint. When disabled, snapshot and phase-report endpoints remain available, while the SSE endpoint returns the controlled `503 SCENE_EVENTS_DISABLED` response below. |

Disabled SSE response:

```http
HTTP/1.1 503 Service Unavailable
Content-Type: application/json

{"msg":"Scene event stream is disabled","code":"SCENE_EVENTS_DISABLED","status":503}
```

## Snapshot

```http
GET /agent/scenes/{sceneId}/snapshot
```

Successful response:

```json
{
  "msg": "ok",
  "code": "E0",
  "status": 200,
  "data": {
    "sceneId": "juyiting-main",
    "sceneVersion": 128,
    "generatedAt": 1784253600000,
    "agents": [
      {
        "agentId": "agent-songjiang",
        "personaCode": "songjiang",
        "status": "online"
      }
    ],
    "states": [
      {
        "agentId": "agent-songjiang",
        "personaCode": "songjiang",
        "behavior": "moving_to_discussion",
        "originRegionId": "main-seat",
        "targetRegionId": "council-table",
        "relatedType": "discussion",
        "relatedId": "dlg-0123456789abcdef0123456789abcdef",
        "phase": "moving",
        "stateVersion": 16,
        "startedAt": 1784253590000,
        "expectedArrivalAt": 1784253610000,
        "expiresAt": 1784253900000
      }
    ]
  }
}
```

`sceneVersion` is the durable version for the complete scoped scene. `stateVersion` is monotonic for one agent within that scene. Expired states and states belonging to agents that are not currently visible are omitted.

For `relatedType` values `discussion` and `chat`, `relatedId` is a bounded opaque identifier in the form `dlg-<32 hex>`. Clients must treat it as non-interpretable and must not derive dialogue content, type, or business meaning from it.

Example:

```bash
curl --fail-with-body \
  -H 'Accept: application/json' \
  'https://localhost:10018/agent/scenes/juyiting-main/snapshot'
```

Supply authorization through the deployment's normal secure mechanism; do not place credentials in documentation, URLs, or logs.

## Resumable SSE events

```http
GET /agent/scenes/{sceneId}/events?sinceVersion={version}
Accept: text/event-stream
Last-Event-ID: {version}
```

- `sinceVersion` and `Last-Event-ID` are optional nonnegative integers.
- If both are present, the server resumes after the greater value.
- Normal events are emitted strictly in contiguous `sceneVersion` order.
- A reconnect receives retained persisted events after its cursor, followed by live events.
- Duplicate or delayed lower versions are not emitted again.

Normal frame:

```text
id:129
event:agent-scene-state-updated
data:{"sceneVersion":129,"eventType":"agent-scene-state-updated","state":{"agentId":"agent-songjiang","personaCode":"songjiang","behavior":"returning_home","originRegionId":"bounty-board","targetRegionId":"main-seat","relatedType":"task","relatedId":"task-001","phase":"moving","stateVersion":17,"startedAt":1784253900000,"expectedArrivalAt":1784253920000,"expiresAt":1784254200000},"occurredAt":1784253900000}

```

Each frame contains exactly one JSON `data` line and ends with a blank line.

### Version gaps and retention

The server emits exactly one `resync-required` frame and closes the stream when any of these conditions is detected:

- the cursor predates retained history;
- the cursor is ahead of the durable scene version;
- initial replay or live catch-up cannot remain contiguous;
- retained rows change during the backlog/live handoff.

```text
id:512
event:resync-required
data:{"sceneVersion":512,"eventType":"resync-required","state":null,"occurredAt":null}

```

The resync event contains only the safe event envelope and current durable scene version. After receiving it, discard incremental assumptions, fetch a new snapshot, and reconnect from that snapshot's `sceneVersion`.

Example:

```bash
curl --no-buffer --fail-with-body \
  -H 'Accept: text/event-stream' \
  'https://localhost:10018/agent/scenes/juyiting-main/events?sinceVersion=128'
```

## Phase report

```http
POST /agent/scenes/{sceneId}/phases
Content-Type: application/json
```

Request fields are allowlisted. `phase` is exactly `arrived` or `blocked`.

```json
{
  "reportId": "report-01HZX8V7",
  "agentId": "agent-songjiang",
  "stateVersion": 17,
  "phase": "arrived",
  "regionId": "council-table",
  "occurredAt": 1784253920000
}
```

Accepted response:

```json
{
  "msg": "ok",
  "code": "E0",
  "status": 200,
  "data": {
    "reportId": "report-01HZX8V7",
    "stateVersion": 17,
    "result": "accepted"
  }
}
```

`result` is one of:

- `accepted`: the report changed current semantic state;
- `ignored_stale`: the state version or timing is stale;
- `ignored_duplicate`: the same scoped `reportId` was already finalized with the same safe result.

Reports are idempotent within tenant/client/scene scope. Once a scoped `reportId` is finalized, later submissions with that identifier return `ignored_duplicate`; callers must therefore generate a new identifier for a distinct report.

Example:

```bash
curl --fail-with-body \
  -H 'Content-Type: application/json' \
  -d '{"reportId":"report-01HZX8V7","agentId":"agent-songjiang","stateVersion":17,"phase":"arrived","regionId":"council-table","occurredAt":1784253920000}' \
  'https://localhost:10018/agent/scenes/juyiting-main/phases'
```

## Controlled errors

| HTTP | Code | Meaning |
| --- | --- | --- |
| `400` | `BAD_REQUEST` | Malformed cursor, scene identifier, JSON, or phase fields. |
| `409` | `SCENE_CONFLICT` | The request conflicts with current scoped state or an idempotency reservation. |
| `422` | `SCENE_VALIDATION_FAILED` | The service rejected an otherwise well-formed request. |
| `500` | `SCENE_STREAM_ERROR` | The active event stream failed safely. |
| `500` | `SCENE_ERROR` | An unexpected scene request failure. |
| `503` | `SCENE_EVENTS_DISABLED` | SSE is disabled by configuration; snapshot and phase endpoints remain available. |

Error bodies contain only controlled messages and never include internal exception details.
