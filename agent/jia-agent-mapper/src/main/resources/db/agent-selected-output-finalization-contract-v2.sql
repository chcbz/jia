-- Preserve old immutable request/source digests. All new writers explicitly store 2.
ALTER TABLE agent_selected_output_finalization
  ADD COLUMN contract_version SMALLINT NOT NULL DEFAULT 1,
  ADD CONSTRAINT chk_asof_contract CHECK (contract_version IN (1,2));
