package cn.jia.chat.deliberation;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/** Locators only. Callers must authorize the persisted grant before exposing any owner data. */
@Mapper
public interface ChatDispatchAuthorityMapper {
    @Select("""
            SELECT o.* FROM chat_dispatch_outbox o
            WHERE BINARY o.tenant_id=BINARY #{tenantId} AND BINARY o.client_id=BINARY #{clientId}
              AND BINARY o.dispatch_id=BINARY #{dispatchId} AND o.event_type='DISPATCH'
            LIMIT 2
            """)
    List<ChatDispatchOutboxEntity> findDispatch(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("dispatchId") String dispatchId);

    @Select("""
            SELECT o.* FROM chat_turn t JOIN chat_dispatch_outbox o
              ON o.dispatch_id=t.dispatch_id AND o.turn_id=t.turn_id
              AND o.tenant_id=t.tenant_id AND o.client_id=t.client_id AND o.owner_jiacn=t.owner_jiacn
            WHERE BINARY o.tenant_id=BINARY #{tenantId} AND BINARY o.client_id=BINARY #{clientId}
              AND BINARY o.turn_id=BINARY #{turnId} AND o.event_type='DISPATCH'
            LIMIT 2
            """)
    List<ChatDispatchOutboxEntity> findTurnDispatch(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("turnId") String turnId);
    /** STOP-only projection of the same immutable grant identity, never the active replacement. */
    record StopGrant(Long grantVersion, String state, String targetAgentId) { }

    @org.apache.ibatis.annotations.ConstructorArgs({
            @org.apache.ibatis.annotations.Arg(column="grant_version", javaType=Long.class),
            @org.apache.ibatis.annotations.Arg(column="state", javaType=String.class),
            @org.apache.ibatis.annotations.Arg(column="target_agent_id", javaType=String.class)
    })
    @Select("""
            SELECT grant_version,state,target_agent_id FROM agent_task_execution_grant
            WHERE BINARY tenant_id=BINARY #{tenantId} AND BINARY client_id=BINARY #{clientId}
              AND BINARY owner_jiacn=BINARY #{ownerJiacn} AND BINARY task_id=BINARY #{taskId}
              AND BINARY grant_id=BINARY #{grantId}
            """)
    StopGrant findOriginalGrantForStop(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("taskId") String taskId, @Param("grantId") String grantId);

    /** Cost-only historical authority. Never grants execution or checks current task/grant state. */
    @Select("""
            SELECT EXISTS (
              SELECT 1 FROM agent_task_execution_grant g
              JOIN agent_runtime r ON r.agent_id=g.target_agent_id AND r.binding_id=g.executor_binding_id
                AND BINARY r.tenant_id=BINARY g.tenant_id AND BINARY r.client_id=BINARY g.client_id
                AND BINARY r.owner_jiacn=BINARY g.executor_owner_jiacn
                AND BINARY r.agent_id=BINARY g.target_agent_id
              WHERE g.tenant_id=#{tenantId} AND g.client_id=#{clientId} AND g.grant_id=#{grantId}
                AND BINARY g.tenant_id=BINARY #{tenantId} AND BINARY g.client_id=BINARY #{clientId}
                AND BINARY g.owner_jiacn=BINARY #{ownerJiacn} AND BINARY g.task_id=BINARY #{taskId}
                AND BINARY g.grant_id=BINARY #{grantId} AND BINARY g.target_agent_id=BINARY #{agentId}
                AND BINARY g.executor_owner_jiacn=BINARY #{executorOwner})
            """)
    boolean hasOriginalUsageExecutor(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("taskId") String taskId, @Param("grantId") String grantId,
            @Param("agentId") String agentId, @Param("executorOwner") String executorOwner);

}
