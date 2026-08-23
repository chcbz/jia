package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentTaskWorkspaceRows.ArtifactRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.EventRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.MemberRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.RequestRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.TaskRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.WorkItemRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** C04 byte-exact, read-only snapshot statements with explicit safe-column projections. */
public interface AgentTaskWorkspaceMapper {
    String EXACT_TENANT = """
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
            """;
    String EXACT_CLIENT = """
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
            """;
    String EXACT_TASK = """
              AND CAST(task_id AS BINARY) = CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(task_id) = OCTET_LENGTH(#{taskId})
            """;

    @Select("""
            SELECT tenant_id, client_id, task_id, reward_status, assigned_agent_id,
                   required_abilities, reward, assigned_at, started_at, completed_at,
                   collaboration_mode, risk_level, max_agents, coordinator_agent_id,
                   review_required, task_version, current_event_version
            FROM agent_task_meta
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{taskId}
            """ + EXACT_TENANT + EXACT_CLIENT + EXACT_TASK + """
            LIMIT 1
            """)
    TaskRow findTask(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("taskId") String taskId);

    @Select("""
            SELECT tenant_id, client_id, task_id, agent_id, member_role, member_status,
                   assignment_source, joined_at, accepted_at, started_at, completed_at,
                   last_heartbeat_at, version
            FROM agent_task_member
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{taskId}
              AND agent_id = #{actorAgentId}
            """ + EXACT_TENANT + EXACT_CLIENT + EXACT_TASK + """
              AND CAST(agent_id AS BINARY) = CAST(#{actorAgentId} AS BINARY)
              AND OCTET_LENGTH(agent_id) = OCTET_LENGTH(#{actorAgentId})
            LIMIT 1
            """)
    MemberRow findActorMember(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("taskId") String taskId,
            @Param("actorAgentId") String actorAgentId);

    @Select("""
            SELECT tenant_id, client_id, task_id, agent_id, member_role, member_status,
                   assignment_source, joined_at, accepted_at, started_at, completed_at,
                   last_heartbeat_at, version
            FROM agent_task_member
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{taskId}
            """ + EXACT_TENANT + EXACT_CLIENT + EXACT_TASK + """
            ORDER BY member_role ASC, CAST(agent_id AS BINARY) ASC,
                     OCTET_LENGTH(agent_id) ASC, id ASC
            LIMIT 500
            """)
    List<MemberRow> findMembers(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("taskId") String taskId);

    @Select("""
            SELECT tenant_id, client_id, task_id, work_item_id, title, description,
                   work_type, required_abilities, assignee_agent_id, status, priority,
                   required_item, dependency_json, lease_until, attempt_count, max_attempts,
                   result_artifact_id, submitted_at, completed_at, version
            FROM agent_task_work_item
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{taskId}
            """ + EXACT_TENANT + EXACT_CLIENT + EXACT_TASK + """
            ORDER BY priority DESC, create_time ASC, CAST(work_item_id AS BINARY) ASC,
                     OCTET_LENGTH(work_item_id) ASC, id ASC
            LIMIT 500
            """)
    List<WorkItemRow> findWorkItems(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("taskId") String taskId);

    @Select("""
            SELECT tenant_id, client_id, task_id, request_id, work_item_id,
                   requester_agent_id, target_type, target_id, request_type, status,
                   priority, title, description, due_at, acknowledged_at, version
            FROM agent_task_request
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{taskId}
              AND (
                    (status = 'open'
                     AND CAST(status AS BINARY) = CAST('open' AS BINARY)
                     AND OCTET_LENGTH(status) = OCTET_LENGTH('open'))
                    OR (status = 'acknowledged'
                        AND CAST(status AS BINARY) = CAST('acknowledged' AS BINARY)
                        AND OCTET_LENGTH(status) = OCTET_LENGTH('acknowledged'))
                  )
            """ + EXACT_TENANT + EXACT_CLIENT + EXACT_TASK + """
            ORDER BY priority DESC, create_time ASC, CAST(request_id AS BINARY) ASC,
                     OCTET_LENGTH(request_id) ASC, id ASC
            LIMIT 500
            """)
    List<RequestRow> findOpenRequests(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("taskId") String taskId);

    @Select("""
            SELECT tenant_id, client_id, task_id, artifact_id, work_item_id,
                   producer_agent_id, artifact_type, title,
                   artifact_version, visibility, created_at
            FROM agent_task_artifact
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{taskId}
              AND (
                    (visibility = 'task_members'
                     AND CAST(visibility AS BINARY) = CAST('task_members' AS BINARY)
                     AND OCTET_LENGTH(visibility) = OCTET_LENGTH('task_members'))
                    OR (visibility = 'reviewer'
                        AND CAST(visibility AS BINARY) = CAST('reviewer' AS BINARY)
                        AND OCTET_LENGTH(visibility) = OCTET_LENGTH('reviewer'))
                    OR (visibility = 'private'
                        AND CAST(visibility AS BINARY) = CAST('private' AS BINARY)
                        AND OCTET_LENGTH(visibility) = OCTET_LENGTH('private'))
                  )
              AND (
                    (visibility = 'task_members'
                     AND CAST(visibility AS BINARY) = CAST('task_members' AS BINARY)
                     AND OCTET_LENGTH(visibility) = OCTET_LENGTH('task_members'))
                    OR (producer_agent_id = #{actorAgentId}
                        AND CAST(producer_agent_id AS BINARY) = CAST(#{actorAgentId} AS BINARY)
                        AND OCTET_LENGTH(producer_agent_id) = OCTET_LENGTH(#{actorAgentId})
                        AND ((visibility = 'task_members'
                              AND CAST(visibility AS BINARY) = CAST('task_members' AS BINARY)
                              AND OCTET_LENGTH(visibility) = OCTET_LENGTH('task_members'))
                             OR (visibility = 'reviewer'
                                 AND CAST(visibility AS BINARY) = CAST('reviewer' AS BINARY)
                                 AND OCTET_LENGTH(visibility) = OCTET_LENGTH('reviewer'))
                             OR (visibility = 'private'
                                 AND CAST(visibility AS BINARY) = CAST('private' AS BINARY)
                                 AND OCTET_LENGTH(visibility) = OCTET_LENGTH('private'))))
                    OR (#{reviewerAccess} = TRUE
                        AND visibility = 'reviewer'
                        AND CAST(visibility AS BINARY) = CAST('reviewer' AS BINARY)
                        AND OCTET_LENGTH(visibility) = OCTET_LENGTH('reviewer'))
                    OR (#{coordinatorAccess} = TRUE
                        AND ((visibility = 'reviewer'
                              AND CAST(visibility AS BINARY) = CAST('reviewer' AS BINARY)
                              AND OCTET_LENGTH(visibility) = OCTET_LENGTH('reviewer'))
                             OR (visibility = 'private'
                                 AND CAST(visibility AS BINARY) = CAST('private' AS BINARY)
                                 AND OCTET_LENGTH(visibility) = OCTET_LENGTH('private'))))
                  )
            """ + EXACT_TENANT + EXACT_CLIENT + EXACT_TASK + """
            ORDER BY created_at DESC, CAST(artifact_id AS BINARY) ASC,
                     OCTET_LENGTH(artifact_id) ASC, artifact_version DESC, id DESC
            LIMIT 101
            """)
    List<ArtifactRow> findVisibleArtifacts(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("taskId") String taskId,
            @Param("actorAgentId") String actorAgentId,
            @Param("reviewerAccess") boolean reviewerAccess,
            @Param("coordinatorAccess") boolean coordinatorAccess);

    @Select("""
            SELECT tenant_id, client_id, task_id, artifact_id, work_item_id,
                   producer_agent_id, artifact_type, title,
                   artifact_version, visibility, created_at
            FROM agent_task_artifact
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{taskId}
              AND artifact_id = #{artifactId}
              AND artifact_version = #{artifactVersion}
            """ + EXACT_TENANT + EXACT_CLIENT + EXACT_TASK + """
              AND CAST(artifact_id AS BINARY) = CAST(#{artifactId} AS BINARY)
              AND OCTET_LENGTH(artifact_id) = OCTET_LENGTH(#{artifactId})
            LIMIT 1
            """)
    ArtifactRow findArtifactVersion(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("taskId") String taskId,
            @Param("artifactId") String artifactId,
            @Param("artifactVersion") int artifactVersion);

    @Select("""
            SELECT tenant_id, client_id, task_id, event_version, event_type,
                   actor_type, actor_id, aggregate_type, aggregate_id, event_json, occurred_at
            FROM agent_task_event
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{taskId}
            """ + EXACT_TENANT + EXACT_CLIENT + EXACT_TASK + """
            ORDER BY event_version DESC, id DESC
            LIMIT 101
            """)
    List<EventRow> findLatestEvents(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("taskId") String taskId);
}
