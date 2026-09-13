package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentTaskAcceptedArtifactRow;
import cn.jia.agent.entity.AgentTaskArtifactOutcomeDecisionEntity;
import cn.jia.agent.entity.AgentTaskArtifactOutcomeEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AgentTaskArtifactOutcomeMapper extends BaseMapper<AgentTaskArtifactOutcomeEntity> {
    String EXACT_SCOPE = """
              AND CAST(o.tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(o.tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(o.client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(o.client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(o.task_id AS BINARY) = CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(o.task_id) = OCTET_LENGTH(#{taskId})
            """;

    @Select("""
            SELECT o.*
            FROM agent_task_artifact_outcome o
            WHERE o.tenant_id = #{tenantId}
              AND o.client_id = #{clientId}
              AND o.task_id = #{taskId}
              AND o.artifact_id = #{artifactId}
              AND o.artifact_version = #{artifactVersion}
            """ + EXACT_SCOPE + """
              AND CAST(o.artifact_id AS BINARY) = CAST(#{artifactId} AS BINARY)
              AND OCTET_LENGTH(o.artifact_id) = OCTET_LENGTH(#{artifactId})
            LIMIT 1
            FOR UPDATE
            """)
    AgentTaskArtifactOutcomeEntity selectExactForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId,
            @Param("artifactId") String artifactId,
            @Param("artifactVersion") int artifactVersion);

    @Select("""
            SELECT d.*
            FROM agent_task_artifact_outcome_decision d
            WHERE d.tenant_id = #{tenantId}
              AND d.client_id = #{clientId}
              AND d.task_id = #{taskId}
              AND d.decision_id = #{decisionId}
              AND CAST(d.tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(d.tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(d.client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(d.client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(d.task_id AS BINARY) = CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(d.task_id) = OCTET_LENGTH(#{taskId})
              AND CAST(d.decision_id AS BINARY) = CAST(#{decisionId} AS BINARY)
              AND OCTET_LENGTH(d.decision_id) = OCTET_LENGTH(#{decisionId})
            LIMIT 1
            FOR UPDATE
            """)
    AgentTaskArtifactOutcomeDecisionEntity selectDecisionForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId,
            @Param("decisionId") String decisionId);

    @Insert("""
            INSERT INTO agent_task_artifact_outcome_decision
                (task_id, decision_id, decision_digest,
                 accepted_artifact_id, accepted_artifact_version,
                 accepted_outcome_version, decided_by_agent_id, decided_at,
                 tenant_id, client_id, create_time, update_time)
            VALUES
                (#{taskId}, #{decisionId}, #{decisionDigest},
                 #{acceptedArtifactId}, #{acceptedArtifactVersion},
                 #{acceptedOutcomeVersion}, #{decidedByAgentId}, #{decidedAt},
                 #{tenantId}, #{clientId}, #{createTime}, #{updateTime})
            """)
    int insertDecision(AgentTaskArtifactOutcomeDecisionEntity decision);

    @Update("""
            UPDATE agent_task_artifact_outcome
               SET outcome_state = #{outcomeState},
                   superseded_by_artifact_id = #{supersededByArtifactId},
                   superseded_by_artifact_version = #{supersededByArtifactVersion},
                   decision_id = #{decisionId},
                   decision_digest = #{decisionDigest},
                   decided_by_agent_id = #{decidedByAgentId},
                   decided_at = #{decidedAt},
                   version = #{resultVersion},
                   update_time = #{decidedAt}
             WHERE tenant_id = #{tenantId}
               AND client_id = #{clientId}
               AND task_id = #{taskId}
               AND artifact_id = #{artifactId}
               AND artifact_version = #{artifactVersion}
               AND outcome_state = #{expectedState}
               AND version = #{expectedVersion}
               AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
               AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
               AND CAST(task_id AS BINARY) = CAST(#{taskId} AS BINARY)
               AND OCTET_LENGTH(task_id) = OCTET_LENGTH(#{taskId})
               AND CAST(artifact_id AS BINARY) = CAST(#{artifactId} AS BINARY)
               AND OCTET_LENGTH(artifact_id) = OCTET_LENGTH(#{artifactId})
               AND CAST(outcome_state AS BINARY) = CAST(#{expectedState} AS BINARY)
               AND OCTET_LENGTH(outcome_state) = OCTET_LENGTH(#{expectedState})
            """)
    int updateByVersion(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId,
            @Param("artifactId") String artifactId,
            @Param("artifactVersion") int artifactVersion,
            @Param("expectedState") String expectedState,
            @Param("expectedVersion") long expectedVersion,
            @Param("outcomeState") String outcomeState,
            @Param("supersededByArtifactId") String supersededByArtifactId,
            @Param("supersededByArtifactVersion") Integer supersededByArtifactVersion,
            @Param("decisionId") String decisionId,
            @Param("decisionDigest") String decisionDigest,
            @Param("decidedByAgentId") String decidedByAgentId,
            @Param("decidedAt") long decidedAt,
            @Param("resultVersion") long resultVersion);

    @Select("""
            <script>
            SELECT a.tenant_id, a.client_id, a.task_id, a.artifact_id, a.work_item_id,
                   a.producer_agent_id, a.artifact_type, a.title, a.content_hash,
                   a.artifact_version, a.visibility, a.created_at,
                   o.outcome_state, o.version AS outcome_version,
                   o.decision_id, o.decided_by_agent_id, o.decided_at
              FROM agent_task_artifact_outcome o
              JOIN agent_task_artifact a
                ON a.tenant_id = o.tenant_id
               AND a.client_id = o.client_id
               AND a.task_id = o.task_id
               AND a.artifact_id = o.artifact_id
               AND a.artifact_version = o.artifact_version
             WHERE o.tenant_id = #{tenantId}
               AND o.client_id = #{clientId}
               AND o.task_id = #{taskId}
               AND o.outcome_state = 'accepted'
            """ + EXACT_SCOPE + """
               AND CAST(o.outcome_state AS BINARY) = CAST('accepted' AS BINARY)
               AND OCTET_LENGTH(o.outcome_state) = OCTET_LENGTH('accepted')
               AND CAST(a.tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(a.tenant_id) = OCTET_LENGTH(#{tenantId})
               AND CAST(a.client_id AS BINARY) = CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(a.client_id) = OCTET_LENGTH(#{clientId})
               AND CAST(a.task_id AS BINARY) = CAST(#{taskId} AS BINARY)
               AND OCTET_LENGTH(a.task_id) = OCTET_LENGTH(#{taskId})
               AND CAST(a.artifact_id AS BINARY) = CAST(o.artifact_id AS BINARY)
               AND OCTET_LENGTH(a.artifact_id) = OCTET_LENGTH(o.artifact_id)
               <if test="workItemId != null">
               AND a.work_item_id = #{workItemId}
               AND CAST(a.work_item_id AS BINARY) = CAST(#{workItemId} AS BINARY)
               AND OCTET_LENGTH(a.work_item_id) = OCTET_LENGTH(#{workItemId})
               </if>
               AND (
                    (a.visibility = 'task_members'
                     AND CAST(a.visibility AS BINARY) = CAST('task_members' AS BINARY)
                     AND OCTET_LENGTH(a.visibility) = OCTET_LENGTH('task_members'))
                    OR (a.producer_agent_id = #{actorAgentId}
                        AND CAST(a.producer_agent_id AS BINARY) = CAST(#{actorAgentId} AS BINARY)
                        AND OCTET_LENGTH(a.producer_agent_id) = OCTET_LENGTH(#{actorAgentId}))
                    OR (#{reviewerAccess} = TRUE
                        AND a.visibility = 'reviewer'
                        AND CAST(a.visibility AS BINARY) = CAST('reviewer' AS BINARY)
                        AND OCTET_LENGTH(a.visibility) = OCTET_LENGTH('reviewer'))
                    OR (#{coordinatorAccess} = TRUE
                        AND ((a.visibility = 'reviewer'
                              AND CAST(a.visibility AS BINARY) = CAST('reviewer' AS BINARY)
                              AND OCTET_LENGTH(a.visibility) = OCTET_LENGTH('reviewer'))
                             OR (a.visibility = 'private'
                                 AND CAST(a.visibility AS BINARY) = CAST('private' AS BINARY)
                                 AND OCTET_LENGTH(a.visibility) = OCTET_LENGTH('private'))))
                   )
             ORDER BY o.decided_at DESC, CAST(a.artifact_id AS BINARY) ASC,
                      OCTET_LENGTH(a.artifact_id) ASC, a.artifact_version DESC, o.id DESC
             LIMIT #{limit}
            </script>
            """)
    List<AgentTaskAcceptedArtifactRow> selectAuthoritativeAccepted(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId,
            @Param("workItemId") String workItemId,
            @Param("actorAgentId") String actorAgentId,
            @Param("reviewerAccess") boolean reviewerAccess,
            @Param("coordinatorAccess") boolean coordinatorAccess,
            @Param("limit") int limit);
}
