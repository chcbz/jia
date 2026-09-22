package cn.jia.agent.mapper;

import cn.jia.agent.entity.HallPrivateMarkEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface HallPrivateMarkMapper {
    String SCOPE = " WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn} "
            + HallPrivateCaseMapper.EXACT_SCOPE;
    @Select("SELECT * FROM hall_private_mark" + SCOPE
            + " AND CAST(source_type AS BINARY)=CAST(#{sourceType} AS BINARY)"
            + " AND OCTET_LENGTH(source_type)=OCTET_LENGTH(#{sourceType})"
            + " AND CAST(source_id AS BINARY)=CAST(#{sourceId} AS BINARY)"
            + " AND OCTET_LENGTH(source_id)=OCTET_LENGTH(#{sourceId}) ORDER BY revision DESC LIMIT 1")
    HallPrivateMarkEntity current(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("sourceType") String sourceType, @Param("sourceId") String sourceId);
    @Select("SELECT * FROM hall_private_mark" + SCOPE
            + " AND CAST(operation_key AS BINARY)=CAST(#{operationKey} AS BINARY)"
            + " AND OCTET_LENGTH(operation_key)=OCTET_LENGTH(#{operationKey}) LIMIT 1")
    HallPrivateMarkEntity byKey(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("operationKey") String operationKey);
    @Insert("""
            INSERT INTO hall_private_mark
              (tenant_id,client_id,owner_jiacn,source_type,source_id,revision,operation_key,request_hash,
               archived,snapshot_execution_id,snapshot_state,snapshot_updated_at,
               viewed_execution_id,viewed_manifest_id,updated_at)
            VALUES (#{tenantId},#{clientId},#{ownerJiacn},#{sourceType},#{sourceId},#{revision},#{operationKey},#{requestHash},
                    #{archived},#{snapshotExecutionId},#{snapshotState},#{snapshotUpdatedAt},
                    #{viewedExecutionId},#{viewedManifestId},#{updatedAt})
            """)
    int insert(HallPrivateMarkEntity mark);
}
