package cn.jia.agent.service;
import cn.jia.agent.entity.*;
/** Durable protocol-v2 boundary, including the admission-locked execution handoff. */
public interface AgentCommandInboxV2Service {
    AgentInboxClaim claim(AgentInboxMessage message,String leaseOwner,long now,long leaseMillis);
    AgentCommandV2ExecutionPort.Result executeAdmitted(
            AgentInboxClaimToken token, String ownerJiacn, byte[] wirePayload, long now,
            AgentCommandV2ExecutionPort executionPort);
    AgentInboxResult complete(AgentInboxClaimToken token,AgentInboxDisposition disposition,long now);
}
