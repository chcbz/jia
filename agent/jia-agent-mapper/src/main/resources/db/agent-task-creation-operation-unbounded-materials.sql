-- Explicit maintenance migration, NOT run by the application initializer.
-- Under the existing release/schema lock: record SHOW CREATE TABLE and row count,
-- verify chk_atco_refs is the old ARRAY-and-0..32 constraint (or already migrated).
-- One atomic MySQL ALTER changes only the CHECK; identity/receipt/unique keys stay intact.
ALTER TABLE agent_task_creation_operation
  DROP CHECK chk_atco_refs,
  ADD CONSTRAINT chk_atco_refs CHECK (JSON_TYPE(input_refs_json)='ARRAY');
-- Verify SHOW CREATE TABLE and unchanged row count. No row rewrite/delete is intended.
-- Rollback must first confirm no array longer than 32 exists; never truncate data to downgrade.
