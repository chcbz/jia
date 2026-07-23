package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentTaskAggregationSnapshotRow;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AgentTaskMetaMapper extends BaseMapper<AgentTaskMetaEntity> {
    @Select("""
            SELECT 'member' AS row_type, tenant_id, client_id, task_id,
                   agent_id AS entity_id, member_role AS role, member_status AS status,
                   NULL AS required_item, NULL AS attempt_count, NULL AS max_attempts,
                   NULL AS result_artifact_id, completed_at, version,
                   NULL AS title, NULL AS work_type, NULL AS lease_token,
                   NULL AS lease_until, NULL AS assignee_agent_id
            FROM agent_task_member
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{taskId}
            UNION ALL
            SELECT 'work_item' AS row_type, tenant_id, client_id, task_id,
                   work_item_id AS entity_id, NULL AS role, status, required_item,
                   attempt_count, max_attempts, result_artifact_id, completed_at, version,
                   title, work_type, lease_token, lease_until, assignee_agent_id
            FROM agent_task_work_item
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{taskId}
            ORDER BY row_type, entity_id
            """)
    List<AgentTaskAggregationSnapshotRow> selectAggregationSnapshot(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId);

    @Update("""
            UPDATE agent_task_meta
            SET reward_status = #{rewardStatus},
                started_at = #{startedAt},
                completed_at = #{completedAt},
                failure_reason = #{failureReason},
                update_time = #{updateTime},
                task_version = task_version + 1
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{taskId}
              AND task_version = #{expectedVersion}
            """)
    int updateStatusByVersion(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId,
            @Param("expectedVersion") long expectedVersion,
            @Param("rewardStatus") String rewardStatus,
            @Param("startedAt") Long startedAt,
            @Param("completedAt") Long completedAt,
            @Param("failureReason") String failureReason,
            @Param("updateTime") long updateTime);
}
