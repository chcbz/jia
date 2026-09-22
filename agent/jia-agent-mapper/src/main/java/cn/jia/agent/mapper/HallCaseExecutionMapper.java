package cn.jia.agent.mapper;

import cn.jia.agent.entity.HallCaseExecutionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface HallCaseExecutionMapper extends BaseMapper<HallCaseExecutionEntity> {
    String COLUMNS = "id,tenant_id,client_id,owner_jiacn,case_id,execution_id,revision_no,parent_execution_id,source_output_ref_json,created_at";
    String EXACT_SCOPE = """
               AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
            """;

    // READ_COMMITTED must reach MySQL again after waiting for the legacy output/case lock.
    // A session-cached null from before that wait would race the immutable execution binding.
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT " + COLUMNS + " FROM hall_case_execution"
            + " WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}"
            + " AND execution_id=#{executionId}" + EXACT_SCOPE
            + " AND CAST(execution_id AS BINARY)=CAST(#{executionId} AS BINARY)"
            + " AND OCTET_LENGTH(execution_id)=OCTET_LENGTH(#{executionId}) LIMIT 1")
    HallCaseExecutionEntity findByExecution(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("executionId") String executionId);

    @Select("SELECT " + COLUMNS + " FROM hall_case_execution"
            + " WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}"
            + " AND case_id=#{caseId}" + EXACT_SCOPE
            + " AND CAST(case_id AS BINARY)=CAST(#{caseId} AS BINARY)"
            + " AND OCTET_LENGTH(case_id)=OCTET_LENGTH(#{caseId})"
            + " ORDER BY revision_no ASC")
    List<HallCaseExecutionEntity> listByCase(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("caseId") String caseId);
}
