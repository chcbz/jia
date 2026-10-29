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
