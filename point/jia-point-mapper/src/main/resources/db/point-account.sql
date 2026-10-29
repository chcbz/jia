-- Explicit operator migration, never a startup initializer.
-- The balance is per (business tenant, global user), NOT per OAuth client.
-- Preserve the existing signed-int balance contract and nonnegative invariant.
CREATE TABLE point_account (
    tenant_id varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    jiacn varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    balance int NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    create_time bigint NOT NULL,
    update_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, jiacn),
    CONSTRAINT chk_point_account_balance CHECK (balance >= 0),
    CONSTRAINT chk_point_account_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
