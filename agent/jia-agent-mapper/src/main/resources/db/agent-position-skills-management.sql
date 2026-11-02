-- Controlled upgrade of the pre-existing position authority table, NOT startup DDL.
-- First inspect SHOW CREATE TABLE agent_position_authority. Execute only when
-- agent_position_authority_chk_1 is the original three-permission CHECK from
-- agent-position-skills.sql. A different name/definition requires adapting this
-- pinned migration to that observed schema, not dropping unrelated constraints.
-- No user authority, appointment, product or business data is inserted here.
ALTER TABLE agent_position_authority
 DROP CHECK agent_position_authority_chk_1,
 ADD CONSTRAINT chk_agent_position_permission
 CHECK(permission_key IN ('MANAGE_POSITIONS','ARCHIVE_WRITE','ARCHIVE_PUBLISH','SKILL_REVIEW','SKILL_PUBLISH'));
