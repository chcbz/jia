# A08 Stable Agent Identity Runtime

## Runtime contract

- New persona bindings issue one immutable `agt_<32 lowercase hex>` ID. The binding and
  `agent_identity_registry` row are committed together; the registry starts as `PROVISIONED`.
- First successful `/agent/register` atomically changes `PROVISIONED` to `ACTIVE` in the
  same transaction as the runtime write. Re-registration keeps the same canonical ID.
- External ownership checks require an `ACTIVE` registry row, exact owner scope, the
  audited `binding_id`, an active binding, and an exact runtime projection.
- `SUSPENDED` and `RETIRED` identities fail closed. `builtin-songjiang` is system-only.
- All scoped registry and alias queries keep normal indexed equality predicates and add
  `CAST(... AS BINARY)` plus `OCTET_LENGTH(...)` review. Inputs with padding or control
  characters are rejected rather than trimmed.

## Agent-client compatibility boundary

1. If a request ID is an explicitly registered `LEGACY_CANONICAL`, it remains unchanged.
2. If a scoped `LEGACY_AGENT_ID` alias is hit during registration, the response returns
   its canonical ID. The client must persist that returned ID and use only canonical IDs
   in subsequent protocol messages.
3. Runtime/persona/display name evidence is never used to guess a legacy identity.
4. B08 and later callers must use `AgentIdentityService.requireCanonicalAgentIdInScope`
   or `resolveLegacyAgentIdInScope`; they must not duplicate the canonical regex.

## Reviewed legacy apply

1. Install `db/agent-identity-legacy-manifest-schema.sql`.
2. Export `db/agent-identity-legacy-snapshot.sql` and the A02 dry-run, then review both externally.
3. Insert only explicitly approved rows using
   `db/agent-identity-legacy-manifest-template.sql` as a shape reference.
4. Set `@a08_manifest_batch_id`, `@a08_approved_report_sha256`, and `@a08_operator`.
5. Execute `db/agent-identity-legacy-apply.sql`.

The apply recomputes the complete `agent_persona_binding` snapshot and every approved
source row hash. Added, deleted, or changed bindings abort the whole transaction. Every
success, no-op rerun, and failure is recorded in `agent_identity_legacy_apply_run`.
A rerun accepts only monotonic lifecycle progress and never resurrects a suspended/retired
identity or a revoked alias.
`AUTO_ELIGIBLE` output is never consumed automatically. The historical `lujunyi` ID is
cross-owner and must remain blocked until a reviewer explicitly selects the correct
active binding (production evidence previously indicated binding `5`, which must be
rechecked before approval).
