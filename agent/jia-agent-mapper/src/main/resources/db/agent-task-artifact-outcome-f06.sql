-- M4-F06 bounded additive migration candidate.
-- DDL only: no backfill, no automatic acceptance, and no mutation of existing artifacts.
-- Deployment requires an explicit migration action before the internal outcome service is activated.

CREATE TABLE IF NOT EXISTS agent_task_artifact_outcome (
    id                              BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    task_id                         VARCHAR(100) NOT NULL COMMENT 'Task ID copied for exact scope fencing',
    artifact_id                     VARCHAR(100) NOT NULL COMMENT 'Stable logical artifact ID',
    artifact_version                INT NOT NULL COMMENT 'Exact immutable artifact version',
    outcome_state                   VARCHAR(20) NOT NULL COMMENT 'accepted/superseded',
    superseded_by_artifact_id       VARCHAR(100) DEFAULT NULL COMMENT 'Accepted replacement artifact ID',
    superseded_by_artifact_version  INT DEFAULT NULL COMMENT 'Accepted replacement artifact version',
    decision_id                     VARCHAR(100) NOT NULL COMMENT 'Caller supplied scoped idempotency identity',
    decision_digest                 CHAR(64) NOT NULL COMMENT 'Canonical SHA-256 of exact decision input',
    decided_by_agent_id             VARCHAR(100) NOT NULL COMMENT 'Authorized coordinator/reviewer agent ID',
    decided_at                      BIGINT NOT NULL COMMENT 'Decision time',
    version                         BIGINT NOT NULL COMMENT 'Outcome optimistic lock version; implicit draft is zero',
    tenant_id                       VARCHAR(50) NOT NULL COMMENT 'Owner jiacn scope',
    client_id                       VARCHAR(50) NOT NULL COMMENT 'OAuth/API client scope',
    create_time                     BIGINT DEFAULT NULL COMMENT 'Create time',
    update_time                     BIGINT DEFAULT NULL COMMENT 'Last modified time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_artifact_outcome_version
        (tenant_id, client_id, artifact_id, artifact_version),
    KEY idx_artifact_outcome_task_state
        (tenant_id, client_id, task_id, outcome_state, decided_at, id),
    KEY idx_artifact_outcome_decision
        (tenant_id, client_id, task_id, decision_id, id),
    KEY idx_artifact_outcome_superseded_by
        (tenant_id, client_id, task_id,
         superseded_by_artifact_id, superseded_by_artifact_version),
    CONSTRAINT chk_artifact_outcome_state
        CHECK (outcome_state IN ('accepted', 'superseded')),
    CONSTRAINT chk_artifact_outcome_versions
        CHECK (artifact_version >= 1 AND version >= 1 AND decided_at > 0),
    CONSTRAINT chk_artifact_outcome_digest
        CHECK (CHAR_LENGTH(decision_digest) = 64),
    CONSTRAINT chk_artifact_outcome_supersession
        CHECK (
            (outcome_state = 'accepted'
             AND superseded_by_artifact_id IS NULL
             AND superseded_by_artifact_version IS NULL)
            OR
            (outcome_state = 'superseded'
             AND superseded_by_artifact_id IS NOT NULL
             AND superseded_by_artifact_version >= 1
             AND (artifact_id <> superseded_by_artifact_id
                  OR artifact_version <> superseded_by_artifact_version))
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='Scoped current accepted/superseded state for immutable task artifact versions';

CREATE TABLE IF NOT EXISTS agent_task_artifact_outcome_decision (
    id                          BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    task_id                     VARCHAR(100) NOT NULL COMMENT 'Task ID for scoped idempotency',
    decision_id                 VARCHAR(100) NOT NULL COMMENT 'Stable caller supplied decision identity',
    decision_digest             CHAR(64) NOT NULL COMMENT 'Canonical SHA-256 of exact decision input',
    accepted_artifact_id        VARCHAR(100) NOT NULL COMMENT 'Artifact accepted by this decision',
    accepted_artifact_version   INT NOT NULL COMMENT 'Exact immutable artifact version accepted',
    accepted_outcome_version    BIGINT NOT NULL COMMENT 'Accepted outcome version returned on replay',
    decided_by_agent_id         VARCHAR(100) NOT NULL COMMENT 'Authorized coordinator/reviewer agent ID',
    decided_at                  BIGINT NOT NULL COMMENT 'Original committed decision time',
    tenant_id                   VARCHAR(50) NOT NULL COMMENT 'Owner jiacn scope',
    client_id                   VARCHAR(50) NOT NULL COMMENT 'OAuth/API client scope',
    create_time                 BIGINT DEFAULT NULL COMMENT 'Create time',
    update_time                 BIGINT DEFAULT NULL COMMENT 'Last modified time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_artifact_outcome_decision
        (tenant_id, client_id, task_id, decision_id),
    KEY idx_artifact_outcome_decision_accepted
        (tenant_id, client_id, task_id,
         accepted_artifact_id, accepted_artifact_version),
    CONSTRAINT chk_artifact_outcome_decision_versions
        CHECK (accepted_artifact_version >= 1
               AND accepted_outcome_version >= 1
               AND decided_at > 0),
    CONSTRAINT chk_artifact_outcome_decision_digest
        CHECK (CHAR_LENGTH(decision_digest) = 64)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='Immutable scoped F06 decision identities and exact replay results';
