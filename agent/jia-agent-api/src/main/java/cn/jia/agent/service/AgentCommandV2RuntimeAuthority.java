package cn.jia.agent.service;

import cn.jia.core.tenant.WorkerScope;

/** Revalidates the current runtime registration before one protocol-v2 send. */
public interface AgentCommandV2RuntimeAuthority {
    AgentCommandV2RuntimeGateway.DispatchResult authorizeAndDispatch(
            WorkerScope workerScope, AgentCommandV2ExecutionPort.Command command,
            AgentCommandV2RuntimeGateway gateway);
}
