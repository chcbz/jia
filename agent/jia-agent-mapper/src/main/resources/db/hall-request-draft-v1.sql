-- JYT-UX DRAFT-v1 additive schema. No business backfill and no execution/task side effects.
CREATE TABLE IF NOT EXISTS hall_request_draft (
    draft_id                  VARCHAR(100) NOT NULL COMMENT 'Opaque draft identifier',
    tenant_id                 VARCHAR(50) NOT NULL COMMENT 'Server-derived single tenant literal',
    client_id                 VARCHAR(50) NOT NULL COMMENT 'Server-derived OAuth client scope',
    owner_jiacn               VARCHAR(50) NOT NULL COMMENT 'Server-derived authenticated owner',
    kind                      VARCHAR(24) NOT NULL COMMENT 'CREATE/REVISION/TASK_CREATE/TASK_ACTION',
    origin_ref                VARCHAR(120) NOT NULL COMMENT 'Bounded UI origin, never an authority',
    source_type               VARCHAR(32) DEFAULT NULL COMMENT 'Verified FILE/CONVERSATION/TASK/EXECUTION_OUTPUT',
    source_id                 VARCHAR(100) DEFAULT NULL COMMENT 'Verified source identifier',
    source_version            INT DEFAULT NULL COMMENT 'Pinned source version when applicable',
    case_id                   VARCHAR(100) DEFAULT NULL COMMENT 'Reserved for B01B private case binding',
    task_id                   VARCHAR(100) DEFAULT NULL COMMENT 'Existing owner-scoped task reference',
    conversation_id           VARCHAR(100) DEFAULT NULL COMMENT 'Existing owner-scoped conversation reference',
    title                     VARCHAR(200) DEFAULT NULL COMMENT 'Incomplete draft title is allowed',
    instruction               MEDIUMTEXT COMMENT 'Private request body, never log',
    target_agent_id           VARCHAR(100) DEFAULT NULL COMMENT 'Verified owner-scoped target Agent',
    output_mime               VARCHAR(160) DEFAULT NULL COMMENT 'Allow-listed requested output MIME',
    inputs_json               MEDIUMTEXT NOT NULL COMMENT 'Canonical fixed file/version selections',
    source_output_ref_json    TEXT COMMENT 'Canonical verified immutable output reference',
    ui_checkpoint_json        MEDIUMTEXT COMMENT 'Reserved non-authoritative UI checkpoint',
    revision                  BIGINT NOT NULL DEFAULT 1 COMMENT 'Draft CAS revision',
    state                     VARCHAR(16) NOT NULL DEFAULT 'EDITING' COMMENT 'EDITING/SUBMITTED/DISCARDED',
    submission_ref            VARCHAR(100) DEFAULT NULL COMMENT 'Reserved B01B submission receipt',
    submitted_execution_id    VARCHAR(100) DEFAULT NULL COMMENT 'Reserved B01B execution binding',
    create_key                VARCHAR(100) NOT NULL COMMENT 'Create idempotency key',
    create_hash               CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Canonical create SHA-256',
    submit_key                VARCHAR(100) DEFAULT NULL COMMENT 'Reserved B01B submit idempotency key',
    submit_hash               CHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT 'Reserved B01B submit hash',
    discard_key               VARCHAR(100) DEFAULT NULL COMMENT 'Discard idempotency key',
    discard_hash              CHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT 'Canonical discard SHA-256',
    created_at                BIGINT NOT NULL COMMENT 'Creation epoch millis',
    updated_at                BIGINT NOT NULL COMMENT 'Stable list cursor epoch millis',
    PRIMARY KEY (draft_id),
    UNIQUE KEY uk_hall_draft_create (tenant_id, client_id, owner_jiacn, create_key),
    UNIQUE KEY uk_hall_draft_submit (tenant_id, client_id, owner_jiacn, submit_key),
    UNIQUE KEY uk_hall_draft_discard (tenant_id, client_id, owner_jiacn, discard_key),
    KEY idx_hall_draft_recovery (tenant_id, client_id, owner_jiacn, state, updated_at, draft_id),
    CONSTRAINT chk_hall_draft_tenant CHECK (tenant_id = '0'),
    CONSTRAINT chk_hall_draft_kind CHECK (kind IN ('CREATE','REVISION','TASK_CREATE','TASK_ACTION')),
    CONSTRAINT chk_hall_draft_state CHECK (state IN ('EDITING','SUBMITTED','DISCARDED')),
    CONSTRAINT chk_hall_draft_revision CHECK (revision >= 1 AND revision <= 9007199254740991),
    CONSTRAINT chk_hall_draft_source_tuple CHECK (
        (source_type IS NULL AND source_id IS NULL AND source_version IS NULL)
        OR (source_type='FILE' AND source_id IS NOT NULL AND source_version >= 1)
        OR (source_type IN ('CONVERSATION','TASK','EXECUTION_OUTPUT')
            AND source_id IS NOT NULL AND source_version IS NULL)),
    CONSTRAINT chk_hall_draft_kind_refs CHECK (
        (kind='REVISION' AND source_output_ref_json IS NOT NULL AND task_id IS NULL)
        OR (kind='CREATE' AND source_output_ref_json IS NULL AND task_id IS NULL)
        OR (kind='TASK_CREATE' AND source_output_ref_json IS NULL AND task_id IS NULL)
        OR (kind='TASK_ACTION' AND source_output_ref_json IS NULL AND task_id IS NOT NULL)),
    CONSTRAINT chk_hall_draft_submit_pair CHECK (
        (submit_key IS NULL AND submit_hash IS NULL)
        OR (submit_key IS NOT NULL AND submit_hash IS NOT NULL)),
    CONSTRAINT chk_hall_draft_discard_pair CHECK (
        (discard_key IS NULL AND discard_hash IS NULL)
        OR (discard_key IS NOT NULL AND discard_hash IS NOT NULL)),
    CONSTRAINT chk_hall_draft_lifecycle CHECK (
        (state='EDITING' AND submission_ref IS NULL AND submitted_execution_id IS NULL
            AND discard_key IS NULL AND discard_hash IS NULL)
        OR (state='DISCARDED' AND submission_ref IS NULL AND submitted_execution_id IS NULL
            AND discard_key IS NOT NULL AND discard_hash IS NOT NULL)
        OR (state='SUBMITTED' AND submission_ref IS NOT NULL AND submit_key IS NOT NULL
            AND submit_hash IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
  COMMENT='Owner-scoped Hall request drafts, never an execution queue';
