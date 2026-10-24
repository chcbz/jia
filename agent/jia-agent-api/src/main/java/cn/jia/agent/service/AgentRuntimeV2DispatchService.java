package cn.jia.agent.service;

/** Sole production broker-to-internal-delivery boundary. Returns only after transaction cleanup. */
public interface AgentRuntimeV2DispatchService {
    AgentCommandV2ExecutionPort.Result prepare(AgentCommandV2ExecutionPort.Command command);
    void verifyPrior(long inboxId);
}
