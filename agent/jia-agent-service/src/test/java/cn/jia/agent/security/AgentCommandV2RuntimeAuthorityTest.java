package cn.jia.agent.security;

import cn.jia.agent.config.AgentTaskEventsGate;
import cn.jia.agent.config.AgentTaskEventsProperties;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentInboxClaimToken;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.service.AgentCommandV2ExecutionPort;
import cn.jia.agent.service.AgentCommandV2RuntimeGateway;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.WorkerScope;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import cn.jia.user.security.AccountState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCommandV2RuntimeAuthorityTest {
    private static final String TOKEN = "1".repeat(32);
    private final AgentRuntimeDao runtimes = mock(AgentRuntimeDao.class);
    private final AgentIdentityService identities = mock(AgentIdentityService.class);
    private final ApiKeyService keys = mock(ApiKeyService.class);
    private final AccountSecurityService accounts = mock(AccountSecurityService.class);
    private final AgentCommandV2RuntimeGateway gateway = mock(AgentCommandV2RuntimeGateway.class);
    private final WorkerScope scope = new WorkerScope(TenantId.of("1"),
            DataDomainId.of("cyf"), "local-test", "consumer-a", 3, 5,
            7, "agent-command", 9);
    private final AgentRuntimeAuthenticationService authority =
            new AgentRuntimeAuthenticationService(runtimes, identities, keys, accounts,
                    new AgentTaskEventsGate(new AgentTaskEventsProperties(true,
                            List.of(new AgentTaskEventsProperties.AllowedScope("1", "client-a")))));
    private OauthApiKeyEntity key;

    @BeforeEach
    void setUp() {
        key = new OauthApiKeyEntity().setId("key-a").setClientId("client-a")
                .setJiacn("owner-a").setApiKey("server-secret").setStatus(1);
        key.setTenantId("1");
        when(keys.get("key-a")).thenAnswer(ignored -> key);
        when(accounts.findUniqueByExactJiacn("owner-a")).thenReturn(Optional.of(
                new AccountSecuritySnapshot(1L, "owner-a", AccountState.ACTIVE, 0L)));
        AgentRuntimeEntity runtime = new AgentRuntimeEntity().setAgentId("agent-1")
                .setOwnerJiacn("owner-a").setBindingId(11L).setTokenHash(TOKEN)
                .setStatus("online");
        runtime.setTenantId("1");
        runtime.setClientId("client-a");
        when(runtimes.findByAgentId("agent-1")).thenReturn(runtime);
        AgentIdentityRegistryEntity identity = new AgentIdentityRegistryEntity()
                .setCanonicalAgentId("agent-1");
        when(identities.requireActiveIdentityForBinding(
                "1", "client-a", "owner-a", 11L, "agent-1")).thenReturn(identity);
        when(identities.requireActiveBinding(identity, null))
                .thenReturn(new AgentPersonaBindingEntity().setId(11L));
        when(gateway.dispatch(any(), any(), eq(AgentCommandV2ExecutionPort.SEND_TIMEOUT_MILLIS)))
                .thenReturn(AgentCommandV2RuntimeGateway.DispatchResult.dispatched());
    }

    @Test
    void newerBindingSupersedesOldSessionAndOnlyCurrentRuntimeIsDispatched() {
        authority.bindV2("old-session", "1", "client-a", "owner-a", "agent-1",
                "runtime-old", "key-a", TOKEN, () -> true, scope);
        authority.bindV2("current-session", "1", "client-a", "owner-a", "agent-1",
                "runtime-current", "key-a", TOKEN, () -> true, scope);

        assertEquals(AgentCommandV2RuntimeGateway.Kind.DISPATCHED,
                authority.authorizeAndDispatch(scope, command(), gateway).kind());
        var target = org.mockito.ArgumentCaptor.forClass(
                AgentCommandV2RuntimeGateway.CurrentSession.class);
        verify(gateway).dispatch(target.capture(), any(),
                eq(AgentCommandV2ExecutionPort.SEND_TIMEOUT_MILLIS));
        assertEquals("current-session", target.getValue().sessionId());
        assertEquals("runtime-current", target.getValue().runtimeInstanceId());
    }

    @Test
    void persistentKeyRevocationAndCrossWorkerScopeFailClosedBeforeSend() {
        authority.bindV2("current-session", "1", "client-a", "owner-a", "agent-1",
                "runtime-current", "key-a", TOKEN, () -> true, scope);
        key.setStatus(0);
        assertEquals(AgentCommandV2RuntimeGateway.Kind.REJECTED,
                authority.authorizeAndDispatch(scope, command(), gateway).kind());

        WorkerScope foreign = new WorkerScope(TenantId.of("2"), DataDomainId.of("cyf"),
                "local-test", "consumer-a", 3, 5, 7, "agent-command", 9);
        assertEquals(AgentCommandV2RuntimeGateway.Kind.REJECTED,
                authority.authorizeAndDispatch(foreign, command(), gateway).kind());
        verify(gateway, never()).dispatch(any(), any(), anyLong());
    }

    private AgentCommandV2ExecutionPort.Command command() {
        AgentInboxClaimToken token = new AgentInboxClaimToken(11L,
                "agent-command-dispatch-v2", "1", "cyf", "local-test", 7L,
                "producer-a", "agent-command", 9L, 2, "consumer-a", 3L,
                "client-a", "msg-1", "evt-1", "cmd-1", "task-1", "agent-1",
                "TASK_INVITE", 41L, "lease-a", 9_000L, 1, 4L, 1, 5L, 99_999L);
        return new AgentCommandV2ExecutionPort.Command(token, "owner-a", new byte[]{1});
    }
}
