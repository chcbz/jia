package cn.jia.agent.output.service;

import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.output.OutputAuthorizationException;
import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOutputRuntimeCapabilityServiceImplTest extends BaseMockTest {
    @Mock AgentRuntimeDao runtimeDao;
    @Mock AgentIdentityService identityService;
    AgentOutputRuntimeCapabilityServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AgentOutputRuntimeCapabilityServiceImpl(
                runtimeDao, identityService, true);
        when(identityService.lockActiveCanonicalAgentIdsInScope(
                "owner", "client", "owner", List.of("agent-1")))
                .thenReturn(List.of("agent-1"));
        when(identityService.requireActiveIdentityForBinding(
                "owner", "client", "owner", 7L, "agent-1"))
                .thenReturn(new AgentIdentityRegistryEntity());
        when(runtimeDao.findExactOutputRuntime(
                "owner", "client", "owner", "agent-1", true))
                .thenReturn(runtime(7L));
    }

    @Test
    void registrationMissingExtensionClearsSnapshotUnderRegistrationTokenFence() {
        when(runtimeDao.replaceOutputCapabilities(
                eq("owner"), eq("client"), eq("owner"), eq("agent-1"), eq(7L),
                eq("registration-token"), eq(null), eq(null),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);

        service.replaceAfterRegistration(
                "owner", "client", "agent-1", "runtime-new",
                "registration-token", null);

        verify(runtimeDao).replaceOutputCapabilities(
                eq("owner"), eq("client"), eq("owner"), eq("agent-1"), eq(7L),
                eq("registration-token"), eq(null), eq(null),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void rejectsPrematureR2CapabilityBeforeRuntimeWrite() {
        assertThrows(OutputAuthorizationException.class,
                () -> service.replaceAfterRegistration(
                        "owner", "client", "agent-1", "runtime-new",
                        "registration-token",
                        List.of(OutputConstants.CAPABILITY_DELIVERY_HTTP_V1)));
        verify(runtimeDao, never()).replaceOutputCapabilities(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void delayedOldPresenceCannotRestoreNewRegistrationSnapshot() {
        when(runtimeDao.refreshOutputCapabilities(
                eq("owner"), eq("client"), eq("owner"), eq("agent-1"), eq(7L),
                eq("runtime-old"), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(0);

        assertThrows(OutputAuthorizationException.class,
                () -> service.refreshPresence(
                        "owner", "client", "agent-1", "runtime-old",
                        List.of(OutputConstants.CAPABILITY_HTTP_V1)));
    }

    @Test
    void runtimeBindingMustStillMatchLockedActiveIdentity() {
        when(identityService.requireActiveIdentityForBinding(
                "owner", "client", "owner", 7L, "agent-1"))
                .thenThrow(new IllegalStateException("identity moved to binding 8"));

        assertThrows(IllegalStateException.class,
                () -> service.refreshPresence(
                        "owner", "client", "agent-1", "runtime-new",
                        List.of(OutputConstants.CAPABILITY_HTTP_V1)));
        verify(runtimeDao, never()).refreshOutputCapabilities(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    }

    private AgentRuntimeEntity runtime(long bindingId) {
        AgentRuntimeEntity runtime = new AgentRuntimeEntity();
        runtime.setTenantId("owner");
        runtime.setClientId("client");
        runtime.setOwnerJiacn("owner");
        runtime.setAgentId("agent-1");
        runtime.setBindingId(bindingId);
        return runtime;
    }
}
