package cn.jia.agent.dao;
import cn.jia.agent.entity.AgentRuntimeV2RegistrationRequestEntity;
public interface AgentRuntimeV2RegistrationRequestDao {
    int append(AgentRuntimeV2RegistrationRequestEntity request);
    AgentRuntimeV2RegistrationRequestEntity lockByStableId(String requestId);
}
