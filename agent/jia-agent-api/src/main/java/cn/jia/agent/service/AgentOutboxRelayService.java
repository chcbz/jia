package cn.jia.agent.service;

import cn.jia.agent.entity.AgentOutboxCandidate;
import cn.jia.agent.entity.AgentOutboxClaim;
import cn.jia.agent.entity.AgentOutboxClaimToken;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.agent.entity.AgentOutboxSettleResult;

import java.util.List;

/** Transactional database half of the D03 outbox relay. */
public interface AgentOutboxRelayService {
    List<AgentOutboxCandidate> discover(long now, int requested);

    AgentOutboxClaim claim(AgentOutboxCandidate candidate, String leaseOwner, long now);

    AgentOutboxSettleResult settle(
            AgentOutboxClaimToken token, AgentRabbitPublishResult result, long now);
}
