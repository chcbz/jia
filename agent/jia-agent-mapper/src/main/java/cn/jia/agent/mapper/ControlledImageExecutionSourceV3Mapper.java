package cn.jia.agent.mapper;
import cn.jia.agent.entity.ControlledImageExecutionSourceV3Entity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;
public interface ControlledImageExecutionSourceV3Mapper extends BaseMapper<ControlledImageExecutionSourceV3Entity> {
 @Select("SELECT * FROM agent_controlled_image_execution_source_v3 WHERE tenant_id=#{tenant} AND client_id=#{client} AND owner_jiacn=#{owner} AND execution_id=#{execution} AND CAST(tenant_id AS BINARY)=CAST(#{tenant} AS BINARY) AND CAST(client_id AS BINARY)=CAST(#{client} AS BINARY) AND CAST(owner_jiacn AS BINARY)=CAST(#{owner} AS BINARY) AND CAST(execution_id AS BINARY)=CAST(#{execution} AS BINARY) ORDER BY input_ordinal")
 List<ControlledImageExecutionSourceV3Entity> list(@Param("tenant")String tenant,@Param("client")String client,@Param("owner")String owner,@Param("execution")String execution);
}
