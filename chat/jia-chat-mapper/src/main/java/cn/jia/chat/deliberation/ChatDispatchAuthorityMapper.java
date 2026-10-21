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
}
