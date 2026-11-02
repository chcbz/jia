-- Admission-owned immutable snapshot. Explicit JSON null means no submitted delivery at admission.
CREATE TABLE IF NOT EXISTS chat_typed_review_context (
 tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 owner_jiacn VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 request_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
 request_revision BIGINT NOT NULL,
 basis_json MEDIUMTEXT NOT NULL,
 basis_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at BIGINT NOT NULL,
 PRIMARY KEY (tenant_id,owner_jiacn,client_id,request_id,request_revision),
 CONSTRAINT fk_ctrc_request FOREIGN KEY (tenant_id,owner_jiacn,client_id,request_id,request_revision)
  REFERENCES chat_request (tenant_id,owner_jiacn,client_id,request_id,request_revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
