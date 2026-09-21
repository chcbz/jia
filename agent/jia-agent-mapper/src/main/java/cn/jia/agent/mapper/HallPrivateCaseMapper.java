package cn.jia.agent.mapper;

import cn.jia.agent.entity.HallPrivateCaseEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface HallPrivateCaseMapper extends BaseMapper<HallPrivateCaseEntity> {
    String COLUMNS = "case_id,tenant_id,client_id,owner_jiacn,title,origin_ref,revision,created_at,updated_at";
    String EXACT_SCOPE = """
               AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
            """;

    @Select("SELECT " + COLUMNS + " FROM hall_private_case"
            + " WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}"
            + " AND case_id=#{caseId}" + EXACT_SCOPE
            + " AND CAST(case_id AS BINARY)=CAST(#{caseId} AS BINARY)"
            + " AND OCTET_LENGTH(case_id)=OCTET_LENGTH(#{caseId}) LIMIT 1")
    HallPrivateCaseEntity findExact(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("caseId") String caseId);

    @Select("SELECT " + COLUMNS + " FROM hall_private_case"
            + " WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}"
            + " AND case_id=#{caseId}" + EXACT_SCOPE
            + " AND CAST(case_id AS BINARY)=CAST(#{caseId} AS BINARY)"
            + " AND OCTET_LENGTH(case_id)=OCTET_LENGTH(#{caseId}) LIMIT 1 FOR UPDATE")
    HallPrivateCaseEntity lockExact(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("caseId") String caseId);

    @Update("""
            UPDATE hall_private_case
               SET revision=#{nextRevision},title=#{title},updated_at=#{updatedAt}
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND case_id=#{caseId} AND revision=#{expectedRevision}
            """ + EXACT_SCOPE + """
               AND CAST(case_id AS BINARY)=CAST(#{caseId} AS BINARY)
               AND OCTET_LENGTH(case_id)=OCTET_LENGTH(#{caseId})
            """)
    int updateRevision(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("caseId") String caseId,
            @Param("expectedRevision") long expectedRevision, @Param("nextRevision") long nextRevision,
            @Param("title") String title, @Param("updatedAt") long updatedAt);
}
