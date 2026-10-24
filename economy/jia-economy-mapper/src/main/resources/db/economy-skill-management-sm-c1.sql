-- SM-C1 additive structures. Run only by separately authorized schema maintenance.
-- Existing orders/ledger/identity/result evidence remain untouched. Old wire is not replayed.
CREATE TABLE economy_skill_managed_state (
 tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, agent_id VARCHAR(100) NOT NULL,
 skill_key VARCHAR(100) NOT NULL, skill_revision BIGINT NOT NULL DEFAULT 0,
 active_installation_id VARCHAR(100), active_policy_revision BIGINT, desired_enabled BOOLEAN NOT NULL DEFAULT FALSE,
 admission_enabled BOOLEAN NOT NULL DEFAULT FALSE, pending_change_id VARCHAR(100),
 PRIMARY KEY(tenant_id,client_id,agent_id,skill_key), CHECK(skill_revision>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE economy_skill_change (
 tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, change_id VARCHAR(100) NOT NULL,
 agent_id VARCHAR(100) NOT NULL, skill_key VARCHAR(100) NOT NULL, actor_id VARCHAR(100) NOT NULL,
 owner_jiacn VARCHAR(50) NOT NULL, version BIGINT NOT NULL, dto_json LONGTEXT NOT NULL,
 permissions_json TEXT NOT NULL, runtime_installation_id VARCHAR(100) NOT NULL, runtime_host_id VARCHAR(100) NOT NULL,
 entitlement_id VARCHAR(100), command_id VARCHAR(100), created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
 PRIMARY KEY(tenant_id,client_id,change_id), KEY idx_skill_changes(tenant_id,client_id,agent_id,created_at,change_id),
 CHECK(version>0), CHECK(JSON_VALID(dto_json)), CHECK(JSON_VALID(permissions_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE economy_skill_management_quote (
 tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, quote_id VARCHAR(100) NOT NULL,
 actor_id VARCHAR(100) NOT NULL, request_json LONGTEXT NOT NULL, dto_json LONGTEXT NOT NULL,
 target_json TEXT NOT NULL, details_json LONGTEXT NOT NULL,
 PRIMARY KEY(tenant_id,client_id,quote_id), CHECK(JSON_VALID(request_json)), CHECK(JSON_VALID(dto_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE economy_skill_installation_authorization (
 tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, installation_id VARCHAR(100) NOT NULL,
 change_id VARCHAR(100) NOT NULL, entitlement_id VARCHAR(100) NOT NULL,
 package_json TEXT NOT NULL, details_json LONGTEXT NOT NULL, permissions_json TEXT NOT NULL,
 adoption_evidence_json LONGTEXT NULL,
 permissions_sha256 CHAR(64) NOT NULL, runtime_installation_id VARCHAR(100) NOT NULL, runtime_host_id VARCHAR(100) NOT NULL,
 PRIMARY KEY(tenant_id,client_id,installation_id), KEY idx_skill_auth_change(tenant_id,client_id,change_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE economy_skill_change_command (
 tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, command_id VARCHAR(100) NOT NULL,
 change_id VARCHAR(100) NOT NULL, request_id VARCHAR(100) NOT NULL, message_id VARCHAR(100) NOT NULL,
 data_json LONGTEXT NOT NULL, state VARCHAR(20) NOT NULL, prepared_sha256 CHAR(64), evidence_json LONGTEXT,
 PRIMARY KEY(tenant_id,client_id,command_id), UNIQUE KEY uk_skill_change_message(tenant_id,client_id,message_id),
 KEY idx_skill_change_commands(tenant_id,client_id,change_id), CHECK(JSON_VALID(data_json)), CHECK(evidence_json IS NULL OR JSON_VALID(evidence_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE economy_skill_management_request (
 tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, actor_id VARCHAR(100) NOT NULL,
 method VARCHAR(8) NOT NULL, path VARBINARY(768) NOT NULL, key_hash CHAR(64) NOT NULL,
 request_sha256 CHAR(64) NOT NULL, resource_id VARCHAR(100) NOT NULL,
 PRIMARY KEY(tenant_id,client_id,actor_id,method,path,key_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE economy_skill_management_result (
 tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, message_id VARCHAR(100) NOT NULL,
 result_sha256 CHAR(64) NOT NULL, receipt_json LONGTEXT NOT NULL,
 PRIMARY KEY(tenant_id,client_id,message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
-- No default policies: absence is SKILL_UPGRADE_POLICY_UNSET, never implicit free coverage.
CREATE TABLE economy_skill_upgrade_policy (
 tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
 source_product_version_id VARCHAR(100) NOT NULL, target_product_version_id VARCHAR(100) NOT NULL,
 policy VARCHAR(16) NOT NULL, PRIMARY KEY(tenant_id,client_id,source_product_version_id,target_product_version_id),
 CHECK(policy IN ('COVERED','PAID','NOT_ALLOWED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
-- Multiple exact candidate installations per original order; covered upgrades have no new order.
-- Explicit alterations, applied once against the frozen v0 schema, not application-startup DDL.
ALTER TABLE economy_skill_installation DROP INDEX uk_skill_installation_order,
 MODIFY order_id VARCHAR(100) NULL,
 DROP CHECK chk_skill_installation_envelope,
 ADD CONSTRAINT chk_skill_installation_envelope CHECK (
 schema_version IN (1,2) AND message_type='command.dispatch' AND command_type='SKILL_INSTALL'
 AND request_id=message_id AND attempt>0 AND fencing_token>0 AND delivery_epoch>0);
ALTER TABLE economy_skill_entitlement DROP INDEX uk_skill_entitlement_order,
 DROP INDEX uk_skill_entitlement_skill, MODIFY order_id VARCHAR(100) NULL;
