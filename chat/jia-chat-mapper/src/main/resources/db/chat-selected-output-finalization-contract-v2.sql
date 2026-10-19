-- One atomic ALTER per table. Existing immutable digests remain contract 1, audit-only.
ALTER TABLE chat_selected_output_finalization
  ADD COLUMN contract_version SMALLINT NOT NULL DEFAULT 1,
  ADD CONSTRAINT chk_csof_contract CHECK (contract_version IN (1,2));
