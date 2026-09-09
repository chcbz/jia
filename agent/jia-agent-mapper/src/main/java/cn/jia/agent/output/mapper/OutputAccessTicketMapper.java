package cn.jia.agent.output.mapper;

import cn.jia.agent.output.entity.OutputAccessTicketEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface OutputAccessTicketMapper extends BaseMapper<OutputAccessTicketEntity> {
    @Select("""
            <script>
            SELECT * FROM output_access_ticket WHERE ticket_hash=#{ticketHash} LIMIT 1
            <if test="forUpdate">FOR UPDATE</if>
            </script>
            """)
    OutputAccessTicketEntity findByHash(
            @Param("ticketHash") byte[] ticketHash,
            @Param("forUpdate") boolean forUpdate);

    @Select("""
            SELECT ticket_hash FROM output_access_ticket
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND binding_id=#{bindingId}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(binding_id AS BINARY)=CAST(#{bindingId} AS BINARY)
              AND OCTET_LENGTH(binding_id)=OCTET_LENGTH(#{bindingId})
              AND created_at >= #{since}
            ORDER BY created_at,ticket_hash
            LIMIT 60
            FOR UPDATE
            """)
    List<byte[]> lockRecentHashesForBinding(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("bindingId") String bindingId,
            @Param("since") long since);
}
