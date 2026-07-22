package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface AgentTaskMetaMapper extends BaseMapper<AgentTaskMetaEntity> {
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
