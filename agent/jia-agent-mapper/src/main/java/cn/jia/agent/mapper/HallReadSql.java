package cn.jia.agent.mapper;

import java.util.Map;

/** Only fixed SQL identifiers are interpolated. All user values are bound parameters. */
public final class HallReadSql {
    private HallReadSql() { }

    public static String page(Map<String, Object> parameters) {
        String source = switch ((String) parameters.get("kind")) {
            case "draft" -> drafts();
            case "private" -> privateItems();
            case "task" -> tasks();
            default -> throw new IllegalArgumentException("kind");
        };
        String view = (String) parameters.get("view");
        if (!"recent".equals(view) && !"needsAction".equals(view)) {
            throw new IllegalArgumentException("view");
        }
        String sql = "SELECT * FROM (" + source + ") hall WHERE 1=1";
        if ("needsAction".equals(view)) {
            // No guessed approval/unread state. Gaps are explicitly marked partial by the service.
            sql += " AND (state IS NULL OR CAST(state AS BINARY) IN (CAST('EDITING' AS BINARY),"
                    + " CAST('FAILED' AS BINARY), CAST('failed' AS BINARY)))";
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
                + " c.case_id AS source_id,c.title,e.execution_state AS state,e.target_agent_id,"
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
                + " e.target_agent_id," + updated + " AS updated_at"
                + " FROM agent_personal_workspace_execution e"
                + " WHERE CAST(e.execution_mode AS BINARY)=CAST('PRIVATE' AS BINARY)" + scope("e")
                + " AND NOT EXISTS (SELECT 1 FROM hall_case_execution link WHERE "
                + exact("link.execution_id", "e.execution_id") + scope("link") + ")";
        return cases + " UNION ALL " + legacy;
    }

    private static String tasks() {
        // Same task-root owner ACL and scoped plan join as AgentTaskMetaMapper task search.
        // No title means unavailable metadata, not permission to query a foreign task_plan.
        return "SELECT t.tenant_id,t.client_id,t.owner_jiacn,'TASK' AS source_type,"
                + " t.task_id AS source_id,p.name AS title,t.reward_status AS state,"
                + " t.assigned_agent_id AS target_agent_id,"
                + " GREATEST(COALESCE(t.update_time,t.create_time),"
                + " COALESCE(p.update_time,p.create_time,t.update_time,t.create_time)) AS updated_at"
                + " FROM agent_task_meta t LEFT JOIN task_plan p ON t.task_id REGEXP '^[0-9]+$'"
                + " AND p.id=CAST(t.task_id AS UNSIGNED)"
                + " AND " + exact("p.jiacn", "#{ownerJiacn}")
                + " AND " + exact("p.client_id", "#{clientId}")
                + " AND " + exact("p.tenant_id", "#{tenantId}")
                + " WHERE 1=1" + scope("t");
    }
}
