-- Explicit operator-only DDL, not startup initialization. Empty client_id denotes
-- environment affiliation without an application binding; it never grants access.
CREATE TABLE user_business_membership (
    tenant_id varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    client_id varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '',
    user_id bigint NOT NULL,
    create_time bigint NOT NULL,
    PRIMARY KEY (tenant_id, client_id, user_id),
    KEY idx_user_business_membership_user (user_id, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
