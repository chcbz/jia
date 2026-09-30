package cn.jia.agent.dao;
import cn.jia.agent.entity.AgentRuntimeV2ReconnectChallengeEntity;
public interface AgentRuntimeV2ReconnectChallengeDao {
    int append(AgentRuntimeV2ReconnectChallengeEntity challenge);
    AgentRuntimeV2ReconnectChallengeEntity lockByRequestStableId(String requestId);
    AgentRuntimeV2ReconnectChallengeEntity lockByChallengeStableId(String challengeId);
}
