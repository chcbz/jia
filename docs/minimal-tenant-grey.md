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
