package cn.jia.agent.dao;
import cn.jia.agent.entity.AgentRuntimeV2RegistrationReceiptEntity;
public interface AgentRuntimeV2RegistrationReceiptDao {
    int append(AgentRuntimeV2RegistrationReceiptEntity receipt);
    AgentRuntimeV2RegistrationReceiptEntity lockByStableId(String receiptId);
    AgentRuntimeV2RegistrationReceiptEntity lockHistoricalByBindingGeneration(String bindingId,long generation);
}
