-- Controlled maintenance only, with application writes stopped and verified backup.
-- Copy exact existing full inputs; never reconstruct from lossy task_plan. Re-run never overwrites current edits.
INSERT INTO agent_task_current_requirement
 (tenant_id,client_id,owner_jiacn,task_id,revision,title,description,content_sha256,source)
SELECT s.tenant_id,s.client_id,s.owner_jiacn,s.task_id,s.revision,s.title,s.description,s.content_sha256,s.source
FROM agent_task_requirement_snapshot s
WHERE NOT EXISTS (SELECT 1 FROM agent_task_requirement_snapshot n
 WHERE n.tenant_id=s.tenant_id AND n.client_id=s.client_id AND n.owner_jiacn=s.owner_jiacn
 AND n.task_id=s.task_id AND n.revision>s.revision)
AND NOT EXISTS (SELECT 1 FROM agent_task_current_requirement c
 WHERE c.tenant_id=s.tenant_id AND c.client_id=s.client_id AND c.owner_jiacn=s.owner_jiacn AND c.task_id=s.task_id);
