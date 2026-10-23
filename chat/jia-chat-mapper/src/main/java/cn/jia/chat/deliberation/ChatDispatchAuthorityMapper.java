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
            SELECT o.* FROM chat_dispatch_outbox o
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

}
