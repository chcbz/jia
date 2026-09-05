package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.entity.AgentPersonaEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/** Hosting admission locks existing business roots; no parallel identity registry is introduced. */
public interface AgentHostingRentBindingMapper {
    @Select("SELECT * FROM agent_persona WHERE persona_code=#{personaCode}"
            + " AND CAST(persona_code AS BINARY)=CAST(#{personaCode} AS BINARY)"
            + " AND OCTET_LENGTH(persona_code)=OCTET_LENGTH(#{personaCode}) FOR UPDATE")
    AgentPersonaEntity lockPersona(@Param("personaCode") String personaCode);

    @Select("SELECT * FROM agent_persona_binding WHERE client_id=#{clientId} AND persona_code=#{personaCode} AND status=1"
            + " AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)"
            + " AND CAST(persona_code AS BINARY)=CAST(#{personaCode} AS BINARY)"
            + " AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})"
            + " AND OCTET_LENGTH(persona_code)=OCTET_LENGTH(#{personaCode}) ORDER BY id FOR UPDATE")
    List<AgentPersonaBindingEntity> lockBindings(@Param("clientId") String clientId,
            @Param("personaCode") String personaCode);
}
