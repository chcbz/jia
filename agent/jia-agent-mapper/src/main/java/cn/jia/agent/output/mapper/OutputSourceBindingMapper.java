package cn.jia.agent.output.mapper;

import cn.jia.agent.output.entity.OutputSourceBindingEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface OutputSourceBindingMapper extends BaseMapper<OutputSourceBindingEntity> {
    @Select("""
            <script>
            SELECT * FROM output_source_binding
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
              AND source_type=#{sourceType} AND source_id=#{sourceId}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(source_type AS BINARY)=CAST(#{sourceType} AS BINARY)
              AND OCTET_LENGTH(source_type)=OCTET_LENGTH(#{sourceType})
              AND CAST(source_id AS BINARY)=CAST(#{sourceId} AS BINARY)
              AND OCTET_LENGTH(source_id)=OCTET_LENGTH(#{sourceId})
            LIMIT 1
            <if test="forUpdate">FOR UPDATE</if>
            </script>
            """)
    OutputSourceBindingEntity findExact(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("sourceType") String sourceType,
            @Param("sourceId") String sourceId,
            @Param("forUpdate") boolean forUpdate);
}
