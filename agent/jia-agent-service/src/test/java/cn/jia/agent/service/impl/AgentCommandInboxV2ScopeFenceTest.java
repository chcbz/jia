package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentCommandInboxV2Dao;
import cn.jia.agent.entity.AgentInboxConsumers;
import cn.jia.agent.entity.AgentInboxIdentityConflictException;
import cn.jia.agent.entity.AgentInboxMessage;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TenantModuleAdmissionGuard;
import cn.jia.core.tenant.WorkerScope;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AgentCommandInboxV2ScopeFenceTest {
    private static final WorkerScope SCOPE = new WorkerScope(
            TenantId.of("1"), DataDomainId.of("cyf"), "local-test", "consumer-a",
            3, 4, 7, "agent-command", 9);

    @Test
    void eachAdjacentAuthorityScopePerformsZeroDbAndZeroAdmissionWork() {
        List<AgentInboxMessage> adjacent = List.of(
                message("2", "cyf", "local-test", 7, "agent-command", 9, "consumer-a", 3),
                message("1", "other", "local-test", 7, "agent-command", 9, "consumer-a", 3),
                message("1", "cyf", "shared", 7, "agent-command", 9, "consumer-a", 3),
                message("1", "cyf", "local-test", 8, "agent-command", 9, "consumer-a", 3),
                message("1", "cyf", "local-test", 7, "other-module", 9, "consumer-a", 3),
                message("1", "cyf", "local-test", 7, "agent-command", 10, "consumer-a", 3),
                message("1", "cyf", "local-test", 7, "agent-command", 9, "consumer-b", 3),
                message("1", "cyf", "local-test", 7, "agent-command", 9, "consumer-a", 4));

        for (AgentInboxMessage message : adjacent) {
            AgentCommandInboxV2Dao dao = mock(AgentCommandInboxV2Dao.class);
            TenantModuleAdmissionGuard guard = mock(TenantModuleAdmissionGuard.class);
            PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
            AgentCommandInboxV2ServiceImpl service =
                    new AgentCommandInboxV2ServiceImpl(dao, SCOPE, guard, tx);

            assertThrows(AgentInboxIdentityConflictException.class,
                    () -> service.claim(message, "lease", 100, 10));
            verifyNoInteractions(dao, guard, tx);
        }
    }

    @Test
    void missingAuthorityIsRejectedAtConstruction() {
        assertThrows(NullPointerException.class,
                () -> new AgentCommandInboxV2ServiceImpl(
                        mock(AgentCommandInboxV2Dao.class), null,
                        mock(TenantModuleAdmissionGuard.class),
                        mock(PlatformTransactionManager.class)));
    }

    private static AgentInboxMessage message(String tenant, String domain, String pool,
            long admission, String module, long migration, String deployment, long generation) {
        return new AgentInboxMessage(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V2,
                tenant, domain, pool, admission, "producer-a", module, migration, 2,
                deployment, generation, "client-a", "msg", "evt", "cmd",
                "task-1", "agent-1", "TASK_INVITE", 1, new byte[]{1});
    }
}
