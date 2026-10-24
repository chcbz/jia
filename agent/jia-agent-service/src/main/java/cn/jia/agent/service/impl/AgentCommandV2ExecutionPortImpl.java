package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.entity.AgentInboxClaimToken;
import cn.jia.agent.service.AgentCommandV2ExecutionPort;
import cn.jia.agent.service.AgentCommandV2RuntimeAuthority;
import cn.jia.agent.service.AgentCommandV2RuntimeGateway;
import cn.jia.agent.service.AgentRuntimeV2DispatchService;
import cn.jia.core.tenant.WorkerScope;

import java.util.Objects;

/** Production protocol-v2 execution port. */
public final class AgentCommandV2ExecutionPortImpl implements AgentCommandV2ExecutionPort {
    static final long RETRY_AFTER_MILLIS = 5_000L;
    private final WorkerScope workerScope;
    private final AgentCommandV2RuntimeAuthority authority;
    private final AgentCommandV2RuntimeGateway gateway;
    private final AgentRuntimeV2DispatchService durable;

    public AgentCommandV2ExecutionPortImpl(WorkerScope workerScope,
            AgentCommandV2RuntimeAuthority authority, AgentCommandV2RuntimeGateway gateway) {
        this.workerScope = Objects.requireNonNull(workerScope, "workerScope");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.durable = null;
    }

    public AgentCommandV2ExecutionPortImpl(WorkerScope scope, AgentRuntimeV2DispatchService durable) {
        this.workerScope=Objects.requireNonNull(scope);this.durable=Objects.requireNonNull(durable);
        this.authority=null;this.gateway=null;
    }

    @Override public boolean durableHandoff(){return durable!=null;}
    @Override public void verifyPrior(long inboxId){if(durable!=null)durable.verifyPrior(inboxId);}

    @Override
    public Result execute(Command command) {
        if(durable!=null)AgentRuntimeV2DispatchServiceImpl.outside();
        if (command == null || !claimMatchesWorker(command.claimToken())
                || !AgentCommandV2WireVerifier.matches(
                command.claimToken(), command.ownerJiacn(), command.wirePayload())) {
            return Result.rejected();
        }
        if(durable!=null)return durable.prepare(command);
        AgentCommandV2RuntimeGateway.DispatchResult dispatched;
        try {
            dispatched = authority.authorizeAndDispatch(workerScope, command, gateway);
        } catch (RuntimeException denied) {
            return Result.rejected();
        }
        if (dispatched == null) return Result.rejected();
        return switch (dispatched.kind()) {
            case DISPATCHED -> Result.dispatched();
            case OFFLINE, SEND_FAILED -> Result.retryable(RETRY_AFTER_MILLIS);
            case REJECTED -> Result.rejected();
        };
    }

    private boolean claimMatchesWorker(AgentInboxClaimToken token) {
        return token != null && token.protocolVersion() == AgentCommandAmqpContract.WIRE_VERSION
                && workerScope.tenantId().value().equals(token.tenantId())
                && workerScope.dataDomainId().value().equals(token.dataDomainId())
                && workerScope.executionPoolId().equals(token.executionPoolId())
                && workerScope.deploymentId().equals(token.claimantDeploymentId())
                && workerScope.workerGeneration() == token.claimantWorkerGeneration()
                && workerScope.admissionEpoch() == token.admissionEpoch()
                && workerScope.moduleId().equals(token.moduleId())
                && workerScope.migrationEpoch() == token.migrationEpoch();
    }
}
