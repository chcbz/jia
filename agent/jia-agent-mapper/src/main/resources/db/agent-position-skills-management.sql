-- Controlled upgrade of the pre-existing position authority table, NOT startup DDL.
-- First inspect SHOW CREATE TABLE agent_position_authority. Execute only when
-- agent_position_authority_chk_1 is the original three-permission CHECK from
-- agent-position-skills.sql. A different name/definition requires adapting this
-- pinned migration to that observed schema, not dropping unrelated constraints.
-- No user authority, appointment, product or business data is inserted here.
-- Permission vocabulary and authorization belong to the API business layer.
ALTER TABLE agent_position_authority
 DROP CHECK agent_position_authority_chk_1;
