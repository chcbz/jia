package cn.jia.agent.mapper;

import java.util.Map;

/** Only fixed SQL identifiers are interpolated. All user values are bound parameters. */
public final class HallReadSql {
    private HallReadSql() { }

    public static String page(Map<String, Object> parameters) {
        String source = switch ((String) parameters.get("kind")) {
            case "draft" -> drafts();
            case "private" -> privateItems();
            case "task" -> tasks(Boolean.TRUE.equals(parameters.get("formalEnabled")));
            default -> throw new IllegalArgumentException("kind");
        };
        String view = (String) parameters.get("view");
        if (!"recent".equals(view) && !"needsAction".equals(view) && !"archive".equals(view)) {
            throw new IllegalArgumentException("view");
        }
        String sql = "SELECT * FROM (" + source + ") hall WHERE 1=1";
        String kind = (String) parameters.get("kind");
        if ("private".equals(kind)) {
            sql += " AND archived=" + ("archive".equals(view) ? "1" : "0");
            if ("needsAction".equals(view)) {
                sql += " AND (state IS NULL OR CAST(state AS BINARY)=CAST('FAILED' AS BINARY)"
                        + " OR (CAST(state AS BINARY)=CAST('OUTPUT_COMMITTED' AS BINARY)"
                        + " AND (viewed_execution_id IS NULL OR CAST(viewed_execution_id AS BINARY)<>CAST(execution_id AS BINARY))))";
            }
        } else if ("task".equals(kind)) {
            if ("archive".equals(view)) sql += " AND CAST(state AS BINARY)=CAST('archived' AS BINARY)";
            else sql += " AND CAST(state AS BINARY)<>CAST('archived' AS BINARY)";
            if ("needsAction".equals(view)) sql += " AND CAST(state AS BINARY) IN (CAST('failed' AS BINARY),CAST('blocked' AS BINARY),CAST('reviewing' AS BINARY))";
        } else if ("archive".equals(view)) {
            throw new IllegalArgumentException("Draft discard is not an archive mark");
        }
        if (parameters.get("q") != null && !parameters.get("q").equals("")) {
            // Literal substring: %, _ and backslash do not become wildcard operators.
            sql += " AND LOCATE(LOWER(#{q}), LOWER(COALESCE(title,''))) > 0";
        }
        if (parameters.get("beforeUpdatedAt") != null) {
            sql += " AND (updated_at < #{beforeUpdatedAt}"
                    + " OR (updated_at = #{beforeUpdatedAt} AND ("
                    + " CAST(source_type AS BINARY) < CAST(#{beforeSourceType} AS BINARY)"
                    + " OR (CAST(source_type AS BINARY) = CAST(#{beforeSourceType} AS BINARY)"
                    + " AND CAST(source_id AS BINARY) < CAST(#{beforeId} AS BINARY)))))";
        }
        return sql + " ORDER BY updated_at DESC, CAST(source_type AS BINARY) DESC,"
                + " CAST(source_id AS BINARY) DESC LIMIT #{limit}";
    }

    private static String scope(String alias) {
        StringBuilder sql = new StringBuilder();
        for (String[] field : new String[][] {{"tenant_id", "tenantId"},
                {"client_id", "clientId"}, {"owner_jiacn", "ownerJiacn"}}) {
            String column = alias + "." + field[0];
            String parameter = "#{" + field[1] + "}";
            sql.append(" AND ").append(column).append('=').append(parameter)
                    .append(" AND CAST(").append(column).append(" AS BINARY)=CAST(")
                    .append(parameter).append(" AS BINARY)")
                    .append(" AND OCTET_LENGTH(").append(column).append(")=OCTET_LENGTH(")
                    .append(parameter).append(')');
        }
        return sql.toString();
    }

    private static String exact(String left, String right) {
        return left + "=" + right + " AND CAST(" + left + " AS BINARY)=CAST(" + right
                + " AS BINARY) AND OCTET_LENGTH(" + left + ")=OCTET_LENGTH(" + right + ")";
    }

    private static String drafts() {
        return "SELECT d.tenant_id,d.client_id,d.owner_jiacn,'DRAFT' AS source_type,"
                + " d.draft_id AS source_id,d.title,d.state,d.target_agent_id,d.updated_at"
                + " FROM hall_request_draft d WHERE CAST(d.state AS BINARY)=CAST('EDITING' AS BINARY)"
                + scope("d");
    }

    private static String privateItems() {
        String updated = "GREATEST(e.created_at,COALESCE(e.update_time,e.created_at),"
                + "COALESCE(e.failed_at,0),COALESCE(e.revoked_at,0))";
        // Latest declared case revision only. LEFT JOIN preserves corrupt/missing latest executions
        // so service validation reports source error instead of silently hiding the case.
        String cases = "SELECT c.tenant_id,c.client_id,c.owner_jiacn,'PRIVATE_CASE' AS source_type,"
                + " c.case_id AS source_id,c.title,e.execution_state AS state,e.target_agent_id,e.execution_id,"
                + " GREATEST(c.updated_at,COALESCE(" + updated + ",c.updated_at)) AS updated_at"
                + " FROM hall_private_case c LEFT JOIN hall_case_execution link ON "
                + exact("link.case_id", "c.case_id") + scope("link")
                + " AND link.revision_no=c.revision"
                + " LEFT JOIN agent_personal_workspace_execution e ON "
                + exact("e.execution_id", "link.execution_id") + scope("e")
                + " AND CAST(e.execution_mode AS BINARY)=CAST('PRIVATE' AS BINARY)"
                + " WHERE 1=1" + scope("c");
        String legacy = "SELECT e.tenant_id,e.client_id,e.owner_jiacn,'LEGACY_EXECUTION' AS source_type,"
                + " e.execution_id AS source_id,NULL AS title,e.execution_state AS state,"
                + " e.target_agent_id,e.execution_id," + updated + " AS updated_at"
                + " FROM agent_personal_workspace_execution e"
                + " WHERE CAST(e.execution_mode AS BINARY)=CAST('PRIVATE' AS BINARY)" + scope("e")
                + " AND NOT EXISTS (SELECT 1 FROM hall_case_execution link WHERE "
                + exact("link.execution_id", "e.execution_id") + scope("link") + ")";
        String archived = "COALESCE((m.archived=1 AND " + exact("m.snapshot_execution_id", "base.execution_id")
                + " AND " + exact("m.snapshot_state", "base.state") + " AND m.snapshot_updated_at=base.updated_at),0)";
        return "SELECT base.tenant_id,base.client_id,base.owner_jiacn,base.source_type,base.source_id,base.title,base.state,"
                + "base.target_agent_id,base.execution_id,GREATEST(base.updated_at,COALESCE(m.updated_at,0)) AS updated_at,"
                + "COALESCE(m.revision,0) AS mark_revision," + archived + " AS archived,m.viewed_execution_id,m.viewed_manifest_id"
                + " FROM (" + cases + " UNION ALL " + legacy + ") base LEFT JOIN hall_private_mark m ON "
                + exact("m.source_type", "base.source_type") + " AND " + exact("m.source_id", "base.source_id") + scope("m")
                + " AND m.revision=(SELECT MAX(m2.revision) FROM hall_private_mark m2 WHERE "
                + exact("m2.source_type", "base.source_type") + " AND " + exact("m2.source_id", "base.source_id") + scope("m2") + ")";
    }

    private static String tasks(boolean formalEnabled) {
        String reviewColumns = formalEnabled ? ",d.delivery_id,d.work_item_id,d.version AS delivery_version,t.task_version,"
                + "(CAST(t.reward_status AS BINARY)=CAST('reviewing' AS BINARY)"
                + " AND CAST(d.state AS BINARY)=CAST('submitted' AS BINARY) AND d.reviewed_at IS NULL AND d.review_reason IS NULL"
                + " AND CAST(w.status AS BINARY)=CAST('submitted' AS BINARY)"
                + " AND " + exact("w.result_artifact_id", "d.manifest_artifact_id")
                + " AND w.version>=0 AND t.task_version>=0 AND d.version>=0 AND d.revision>=1"
                + " AND d.manifest_artifact_version>=1 AND d.submitted_at>0"
                + " AND EXISTS (SELECT 1 FROM agent_task_formal_delivery_item di WHERE "
                + exact("di.delivery_id", "d.delivery_id") + scopeWithoutOwner("di") + ")"
                + " AND w.lease_token IS NULL AND w.lease_until IS NULL) AS review_ready"
                : ",NULL AS delivery_id,NULL AS work_item_id,NULL AS delivery_version,t.task_version,NULL AS review_ready";
        String joins = formalEnabled ? " LEFT JOIN agent_task_formal_delivery d ON "
                + exact("d.task_id", "t.task_id") + scopeWithoutOwner("d")
                + " AND EXISTS (SELECT 1 FROM agent_task_work_item ownw WHERE " + exact("ownw.task_id", "t.task_id")
                + " AND " + exact("ownw.work_item_id", "d.work_item_id") + scope("ownw") + ")"
                + " AND d.id=(SELECT d2.id FROM agent_task_formal_delivery d2 WHERE "
                + exact("d2.task_id", "t.task_id") + scopeWithoutOwner("d2") + " ORDER BY d2.revision DESC,d2.id DESC LIMIT 1)"
                + " LEFT JOIN agent_task_work_item w ON " + exact("w.task_id", "t.task_id")
                + " AND " + exact("w.work_item_id", "d.work_item_id") + scope("w") : "";
        String time = "GREATEST(COALESCE(t.update_time,t.create_time),COALESCE(p.update_time,p.create_time,t.update_time,t.create_time)"
                + (formalEnabled ? ",COALESCE(d.submitted_at,0),COALESCE(d.reviewed_at,0)" : "") + ")";
        return "SELECT t.tenant_id,t.client_id,t.owner_jiacn,'TASK' AS source_type,t.task_id AS source_id,"
                + "p.name AS title,t.reward_status AS state,t.assigned_agent_id AS target_agent_id," + time + " AS updated_at" + reviewColumns
                + " FROM agent_task_meta t LEFT JOIN task_plan p ON t.task_id REGEXP '^[0-9]+$' AND p.id=CAST(t.task_id AS UNSIGNED)"
                + " AND " + exact("p.jiacn", "#{ownerJiacn}") + " AND " + exact("p.client_id", "#{clientId}")
                + " AND " + exact("p.tenant_id", "#{tenantId}") + joins + " WHERE 1=1" + scope("t");
    }
    private static String scopeWithoutOwner(String alias) {
        return " AND " + exact(alias + ".tenant_id", "#{tenantId}")
                + " AND " + exact(alias + ".client_id", "#{clientId}");
    }
}
