package cn.jia.task.mapper;

import cn.jia.task.entity.TaskPlanEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author chc
 * @since 2021-11-07
 */
public interface TaskPlanMapper extends BaseMapper<TaskPlanEntity> {

    @Select("""
            SELECT * FROM task_plan
            WHERE id = #{id}
              AND tenant_id = #{tenantId} AND client_id = #{clientId} AND jiacn = #{ownerJiacn}
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(jiacn) = OCTET_LENGTH(#{ownerJiacn})
            """)
    TaskPlanEntity findInOwnerScope(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("id") Long id);
}
