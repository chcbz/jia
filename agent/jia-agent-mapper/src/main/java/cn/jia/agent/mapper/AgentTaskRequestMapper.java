package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentTaskRequestDTO;
import cn.jia.agent.entity.AgentTaskRequestEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface AgentTaskRequestMapper extends BaseMapper<AgentTaskRequestEntity> {
    @Update("""
            UPDATE agent_task_request
            SET work_item_id = #{request.workItemId},
                requester_agent_id = #{request.requesterAgentId},
                target_type = #{request.targetType},
                target_id = #{request.targetId},
                request_type = #{request.requestType},
                status = #{request.status},
                priority = #{request.priority},
                title = #{request.title},
                description = #{request.description},
                response_json = #{request.responseJson},
                due_at = #{request.dueAt},
                acknowledged_at = #{request.acknowledgedAt},
                resolved_at = #{request.resolvedAt},
                update_time = #{updateTime},
                version = version + 1
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{taskId}
              AND request_id = #{requestId}
              AND version = #{expectedVersion}
            """)
    int updateByVersion(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId,
            @Param("requestId") String requestId,
            @Param("expectedVersion") long expectedVersion,
            @Param("request") AgentTaskRequestDTO request,
            @Param("updateTime") long updateTime);
}
