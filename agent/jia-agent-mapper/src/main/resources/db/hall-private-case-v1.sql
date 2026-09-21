-- JYT-UX B01B additive private-case and immutable execution-lineage schema.
CREATE TABLE IF NOT EXISTS hall_private_case (
    case_id       VARCHAR(100) NOT NULL COMMENT 'Opaque private case identifier',
    tenant_id     VARCHAR(50) NOT NULL COMMENT 'Server-derived single tenant literal',
    client_id     VARCHAR(50) NOT NULL COMMENT 'Server-derived OAuth client scope',
    owner_jiacn   VARCHAR(50) NOT NULL COMMENT 'Server-derived authenticated owner',
    title         VARCHAR(200) DEFAULT NULL COMMENT 'Latest private case title',
    origin_ref    VARCHAR(120) NOT NULL COMMENT 'Bounded UI origin; never authority',
    revision      BIGINT NOT NULL COMMENT 'Latest immutable execution revision',
    created_at    BIGINT NOT NULL COMMENT 'Creation epoch millis',
    updated_at    BIGINT NOT NULL COMMENT 'Latest change epoch millis',
    PRIMARY KEY (case_id),
    UNIQUE KEY uk_hall_case_scope_id (tenant_id, client_id, owner_jiacn, case_id),
    KEY idx_hall_case_recent (tenant_id, client_id, owner_jiacn, updated_at, case_id),
    CONSTRAINT chk_hall_case_tenant CHECK (tenant_id='0'),
    CONSTRAINT chk_hall_case_revision CHECK (revision>=1 AND revision<=9007199254740991),
    CONSTRAINT chk_hall_case_time CHECK (created_at>=0 AND updated_at>=created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
  COMMENT='Owner-scoped private Hall cases; execution state remains authoritative';

CREATE TABLE IF NOT EXISTS hall_case_execution (
    id                     BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id              VARCHAR(50) NOT NULL COMMENT 'Server-derived single tenant literal',
    client_id              VARCHAR(50) NOT NULL COMMENT 'Server-derived OAuth client scope',
    owner_jiacn            VARCHAR(50) NOT NULL COMMENT 'Server-derived authenticated owner',
    case_id                VARCHAR(100) NOT NULL COMMENT 'Private case identifier',
    execution_id           VARCHAR(100) NOT NULL COMMENT 'Existing workspace execution identifier',
    revision_no            BIGINT NOT NULL COMMENT 'Monotonic case revision number',
    parent_execution_id    VARCHAR(100) DEFAULT NULL COMMENT 'Immutable parent for revisions',
    source_output_ref_json TEXT COMMENT 'Canonical fixed source output reference',
    created_at             BIGINT NOT NULL COMMENT 'Binding epoch millis',
    PRIMARY KEY (id),
    UNIQUE KEY uk_hall_case_execution_scope (tenant_id, client_id, owner_jiacn, execution_id),
    UNIQUE KEY uk_hall_case_revision_scope (tenant_id, client_id, owner_jiacn, case_id, revision_no),
    KEY idx_hall_case_execution_list (tenant_id, client_id, owner_jiacn, case_id, revision_no, execution_id),
    CONSTRAINT chk_hall_case_execution_tenant CHECK (tenant_id='0'),
    CONSTRAINT chk_hall_case_execution_revision CHECK (
        revision_no>=1 AND revision_no<=9007199254740991),
    CONSTRAINT chk_hall_case_execution_lineage CHECK (
        (revision_no=1 AND parent_execution_id IS NULL AND source_output_ref_json IS NULL)
        OR (revision_no>1 AND parent_execution_id IS NOT NULL AND source_output_ref_json IS NOT NULL)),
    CONSTRAINT chk_hall_case_execution_time CHECK (created_at>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
  COMMENT='Immutable owner-scoped private case execution lineage';
