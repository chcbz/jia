package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentOutboxRelaySettings;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.config.AgentRabbitTopologyReadiness;
import cn.jia.agent.dao.AgentOutboxRelayDao;
import cn.jia.agent.entity.AgentOutboxClaimToken;
import cn.jia.agent.entity.AgentOutboxSettleResult;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TenantModuleAdmissionGuard;
import cn.jia.core.tenant.WorkerScope;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AgentOutboxRelayScopeFenceTest {
    private static final WorkerScope SCOPE = new WorkerScope(
            TenantId.of("1"), DataDomainId.of("cyf"), "pool-a", "deployment-a",
            7, 11, 13, "agent-command", 17);

    @Test
    void mismatchedTokenScopeIsStaleBeforeAdmissionOrDaoAccess() {
        AgentOutboxRelayDao dao = mock(AgentOutboxRelayDao.class);
        TenantModuleAdmissionGuard guard = mock(TenantModuleAdmissionGuard.class);
        AgentOutboxRelayServiceImpl service = new AgentOutboxRelayServiceImpl(
                dao, mock(AgentRabbitSafetyGate.class), AgentRabbitTopologyManifest.canonical(),
                mock(AgentRabbitTopologyReadiness.class), mock(AgentOutboxRelaySettings.class),
                guard, mock(PlatformTransactionManager.class));
        AgentOutboxClaimToken token = token(2);

        for (WorkerScope drift : List.of(
                new WorkerScope(TenantId.of("2"), SCOPE.dataDomainId(), SCOPE.executionPoolId(),
                        SCOPE.deploymentId(), 7, 11, 13, "agent-command", 17),
                new WorkerScope(SCOPE.tenantId(), DataDomainId.of("other"), SCOPE.executionPoolId(),
                        SCOPE.deploymentId(), 7, 11, 13, "agent-command", 17),
                new WorkerScope(SCOPE.tenantId(), SCOPE.dataDomainId(), "pool-b",
                        SCOPE.deploymentId(), 7, 11, 13, "agent-command", 17),
                new WorkerScope(SCOPE.tenantId(), SCOPE.dataDomainId(), SCOPE.executionPoolId(),
                        "deployment-b", 7, 11, 13, "agent-command", 17),
                new WorkerScope(SCOPE.tenantId(), SCOPE.dataDomainId(), SCOPE.executionPoolId(),
                        SCOPE.deploymentId(), 8, 11, 13, "agent-command", 17),
                new WorkerScope(SCOPE.tenantId(), SCOPE.dataDomainId(), SCOPE.executionPoolId(),
                        SCOPE.deploymentId(), 7, 11, 14, "agent-command", 17),
                new WorkerScope(SCOPE.tenantId(), SCOPE.dataDomainId(), SCOPE.executionPoolId(),
                        SCOPE.deploymentId(), 7, 11, 13, "other-module", 17),
                new WorkerScope(SCOPE.tenantId(), SCOPE.dataDomainId(), SCOPE.executionPoolId(),
                        SCOPE.deploymentId(), 7, 11, 13, "agent-command", 18))) {
            assertEquals(AgentOutboxSettleResult.STALE,
                    service.settle(drift, token, AgentRabbitPublishResult.ack(), 100));
        }
        assertEquals(AgentOutboxSettleResult.STALE,
                service.settle(SCOPE, token(1), AgentRabbitPublishResult.ack(), 100));
        verifyNoInteractions(dao, guard);
    }

    private static AgentOutboxClaimToken token(int protocolVersion) {
        return new AgentOutboxClaimToken(
                1, 41, "1", "cyf", "pool-a", 13, "producer-a", "agent-command", 17,
                protocolVersion, "deployment-a", 7, "client-a", "evt-1", "msg-1",
                "cmd-1", "task-1", "agent-1", "task.invite", "exchange", "routing",
                new byte[] {1}, new byte[32], 1000,
                AgentOutboxLeaseOwner.create(SCOPE, new UUID(0, 1)), 500,
                1, 1, "PENDING", "msg-1", 1, 1);
    }
}
