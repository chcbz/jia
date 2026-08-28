package cn.jia.agent.service;

import cn.jia.agent.common.AgentHostedProfileState;
import cn.jia.agent.entity.AgentHostedProfileEntity;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.service.AgentHostedBindingTransaction.Prepared;
import cn.jia.agent.service.AgentHostedBindingTransaction.Scope;
import cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentPersonaProvisioningServiceTest {
    private static final Scope SCOPE = new Scope("owner-a", "client-a", "owner-a");
    private static final String AGENT_ID = "agt_0123456789abcdef0123456789abcdef";
    @TempDir Path temp;

    @Test
    void localBindUsesExplicitJwtScopeAndExecutableAbsoluteProfileSection() {
        AgentService agentService = mock(AgentService.class);
        AgentHostedBindingTransaction transactions = mock(AgentHostedBindingTransaction.class);
        AgentHostedProfilePublisher publisher = mock(AgentHostedProfilePublisher.class);
        AgentRuntimeDTO runtime = runtime();
        runtime.setName("Wu Yong");
        runtime.setTitle("Strategist");
        when(agentService.bindPersona("owner-a", "client-a", "owner-a", "wuyong"))
                .thenReturn(runtime);

        var result = new AgentPersonaProvisioningService(agentService, transactions, publisher)
                .bind(SCOPE, "wuyong", "local");

        assertEquals("local", result.getMode());
        assertTrue(result.getProfileExample().startsWith("[agent.wuyong]\n"));
        assertTrue(result.getProfileExample().contains("agentId=" + AGENT_ID));
        assertEquals("/home/isp/apps/codex-ws-agent", result.getWorkdir());
        assertTrue(result.getProfileExample().contains("codexWorkdir=/home/isp/apps/codex-ws-agent"));
        assertTrue(result.getProfileExample().contains("agentName=Wu Yong"));
        assertTrue(result.getProfileExample().contains("personaName=Strategist"));
        assertNull(result.getCodexHome());
        assertFalse(result.getProfileExample().contains("codexHome="));
        assertFalse(result.getProfileExample().contains("$HOME"));
        assertTrue(result.getProfileExample().contains("isDefault=true"));
        assertFalse(result.getProfileExample().contains("apiKey"));
        assertFalse(result.getProfileExample().contains("cdx_"));
        assertTrue(result.getEnvExample().contains("OPENCLAW_API_KEY=<key>"));
        verify(agentService).bindPersona("owner-a", "client-a", "owner-a", "wuyong");
        verifyNoInteractions(transactions, publisher);
    }

    @Test
    void copiedLocalProfilePassesAuthoritativeRuntimeValidation() throws Exception {
        Path runtimeSource = Path.of(
                "/home/isp/wsps/chcbz/isp-install/conf/codex-ws-agent/agent-client.mjs");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(runtimeSource));
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/node")));
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(
                Path.of("/home/isp/apps/codex-ws-agent")));

        AgentService agentService = mock(AgentService.class);
        AgentHostedBindingTransaction transactions = mock(AgentHostedBindingTransaction.class);
        AgentHostedProfilePublisher publisher = mock(AgentHostedProfilePublisher.class);
        AgentRuntimeDTO runtime = runtime();
        runtime.setName("Wu Yong");
        runtime.setTitle("Strategist");
        when(agentService.bindPersona("owner-a", "client-a", "owner-a", "wuyong"))
                .thenReturn(runtime);
        var result = new AgentPersonaProvisioningService(agentService, transactions, publisher)
                .bind(SCOPE, "wuyong", "local");
        Path copiedProfile = temp.resolve("codex-profiles.conf");
        Files.writeString(copiedProfile, result.getProfileExample() + "\n");

        ProcessBuilder validation = new ProcessBuilder(
                "/usr/bin/node", runtimeSource.toString(), "--validate");
        validation.directory(temp.toFile()).redirectErrorStream(true);
        validation.environment().remove("CODEX_PROFILES");
        validation.environment().remove("CODEX_WORKSPACE_POLICIES_FILE");
        validation.environment().put("CODEX_WORKSPACE_POLICIES", "");
        validation.environment().put("CODEX_PROFILES_FILE", copiedProfile.toString());
        validation.environment().put("DEFAULT_CODEX_PROFILE", result.getProfileId());
        validation.environment().put("OPENCLAW_API_KEY", "test-only-key");
        validation.environment().put("CODEX_SESSION_MAP_FILE",
                temp.resolve("codex-session-map.json").toString());
        Process validated = validation.start();
        if (!validated.waitFor(15, TimeUnit.SECONDS)) {
            validated.destroyForcibly();
            fail("runtime validation timed out");
        }
        String output = new String(validated.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, validated.exitValue(), output);
        assertTrue(output.contains("configuration valid | profiles=1"), output);
    }

    @Test
    void suspendingRepairCompletesCrashWindowAndRepeatedRepairIsIdempotent() {
        AgentService agentService = mock(AgentService.class);
        AgentHostedBindingTransaction transactions = mock(AgentHostedBindingTransaction.class);
        AgentHostedProfilePublisher publisher = mock(AgentHostedProfilePublisher.class);
        AgentHostedProfileEntity suspending = hosted(AgentHostedProfileState.SUSPENDING, 4L);
        AgentHostedProfileEntity suspended = hosted(AgentHostedProfileState.SUSPENDED, 5L);
        AgentPersonaEntity persona = persona();
        AgentRuntimeDTO runtime = runtime();
        Prepared first = new Prepared(suspending, runtime, persona, "dedicated-secret");
        Prepared second = new Prepared(suspended, runtime, persona, null);
        AgentHostedProfilePublisher.PublishedPaths paths = paths(false, true);
        when(transactions.resumeRepair(SCOPE, 19L)).thenReturn(first, second);
        when(publisher.publish(suspending, persona, 4L, 5L, false, "dedicated-secret"))
                .thenReturn(paths);
        when(publisher.inspectExisting(suspended, 5L)).thenReturn(paths);
        when(transactions.completeUnbind(SCOPE, 19L, 4L, 5L)).thenReturn(suspended);

        AgentPersonaProvisioningService service =
                new AgentPersonaProvisioningService(agentService, transactions, publisher);
        var completed = service.repair(SCOPE, 19L);
        var repeated = service.repair(SCOPE, 19L);

        assertEquals(AgentHostedProfileState.SUSPENDED, completed.getHostedState());
        assertEquals("19", completed.getBindingId());
        assertEquals("5", completed.getGeneration());
        assertEquals(AgentHostedProfileState.SUSPENDED, repeated.getHostedState());
        assertTrue(repeated.getServerProfileAlreadyExists());
        verify(publisher, times(1)).publish(suspending, persona, 4L, 5L, false, "dedicated-secret");
        verify(publisher, times(1)).inspectExisting(suspended, 5L);
        verify(transactions, times(1)).completeUnbind(SCOPE, 19L, 4L, 5L);
        verify(transactions, never()).markRepair(any(), anyLong(), anyString(),
                any(), anyLong(), anyString(), any());
    }

    @Test
    void idempotentActiveReturnsConfiguredExistingMetadataAndJsonSafeIdentifiers() {
        AgentService agentService = mock(AgentService.class);
        AgentHostedBindingTransaction transactions = mock(AgentHostedBindingTransaction.class);
        AgentHostedProfilePublisher publisher = mock(AgentHostedProfilePublisher.class);
        AgentHostedProfileEntity active = hosted(AgentHostedProfileState.ACTIVE, Long.MAX_VALUE - 2);
        active.setBindingId(Long.MAX_VALUE - 1);
        active.setProfileKey(AgentHostedBindingTransaction.scopeDigest(SCOPE) + active.getBindingId());
        Prepared prepared = new Prepared(active, runtime(), persona(), "dedicated-secret");
        AgentHostedProfilePublisher.PublishedPaths paths = paths(false, true);
        when(transactions.prepareHosted(SCOPE, "wuyong")).thenReturn(prepared);
        when(publisher.inspectExisting(active, Long.MAX_VALUE - 2)).thenReturn(paths);

        var result = new AgentPersonaProvisioningService(agentService, transactions, publisher)
                .bind(SCOPE, "wuyong", "server");

        assertEquals(Long.toString(Long.MAX_VALUE - 1), result.getBindingId());
        assertEquals(Long.toString(Long.MAX_VALUE - 2), result.getGeneration());
        assertEquals("/configured/clients/" + AGENT_ID, result.getWorkdir());
        assertEquals("/configured/runtime/.codex-hosted-profile", result.getCodexHome());
        assertEquals("/configured/runtime/codex-profiles.conf", result.getProfilesFile());
        assertFalse(result.getServerProfileCreated());
        assertTrue(result.getServerProfileAlreadyExists());
        verify(publisher).inspectExisting(active, Long.MAX_VALUE - 2);
        verify(publisher, never()).publish(any(), any(), anyLong(), anyLong(), anyBoolean(), any());
    }

    @Test
    void newActivationAggregatesCreatedMetadataAcrossDisabledAndEnabledStages() {
        AgentService agentService = mock(AgentService.class);
        AgentHostedBindingTransaction transactions = mock(AgentHostedBindingTransaction.class);
        AgentHostedProfilePublisher publisher = mock(AgentHostedProfilePublisher.class);
        AgentPersonaEntity persona = persona();
        AgentRuntimeDTO runtime = runtime();
        AgentHostedProfileEntity preparedHosted = hosted(AgentHostedProfileState.PREPARED, 0L);
        AgentHostedProfileEntity staged = hosted(AgentHostedProfileState.STAGED_DISABLED, 1L);
        AgentHostedProfileEntity fileEnabled = hosted(AgentHostedProfileState.FILE_ENABLED, 2L);
        AgentHostedProfileEntity active = hosted(AgentHostedProfileState.ACTIVE, 2L);
        when(transactions.prepareHosted(SCOPE, "wuyong"))
                .thenReturn(new Prepared(preparedHosted, runtime, persona, "dedicated-secret"));
        when(publisher.publish(preparedHosted, persona, 0L, 1L, false, "dedicated-secret"))
                .thenReturn(paths(true, false));
        when(transactions.transition(SCOPE, 19L, AgentHostedProfileState.PREPARED, 0L,
                AgentHostedProfileState.STAGED_DISABLED, 1L, true)).thenReturn(staged);
        when(publisher.publish(staged, persona, 1L, 2L, true, "dedicated-secret"))
                .thenReturn(paths(false, true));
        when(transactions.transition(SCOPE, 19L, AgentHostedProfileState.STAGED_DISABLED, 1L,
                AgentHostedProfileState.FILE_ENABLED, 2L, true)).thenReturn(fileEnabled);
        when(transactions.transition(SCOPE, 19L, AgentHostedProfileState.FILE_ENABLED, 2L,
                AgentHostedProfileState.ACTIVE, 2L, true)).thenReturn(active);

        var result = new AgentPersonaProvisioningService(agentService, transactions, publisher)
                .bind(SCOPE, "wuyong", "server");

        assertEquals(AgentHostedProfileState.ACTIVE, result.getHostedState());
        assertTrue(result.getServerProfileCreated());
        assertFalse(result.getServerProfileAlreadyExists());
        assertEquals("/configured/runtime/codex-profiles.conf", result.getProfilesFile());
        verify(publisher, never()).inspectExisting(any(), anyLong());
    }

    @Test
    void staleLosingBindFailureMarksOnlyItsExpectedCheckpoint() {
        AgentService agentService = mock(AgentService.class);
        AgentHostedBindingTransaction transactions = mock(AgentHostedBindingTransaction.class);
        AgentHostedProfilePublisher publisher = mock(AgentHostedProfilePublisher.class);
        AgentHostedProfileEntity preparedHosted = hosted(AgentHostedProfileState.PREPARED, 0L);
        AgentPersonaEntity persona = persona();
        Prepared prepared = new Prepared(preparedHosted, runtime(), persona, "dedicated-secret");
        AgentBizException conflict = new AgentBizException("AGENT_ERROR", "concurrent winner");
        when(transactions.prepareHosted(SCOPE, "wuyong")).thenReturn(prepared);
        when(publisher.publish(preparedHosted, persona, 0L, 1L, false, "dedicated-secret"))
                .thenReturn(paths(false, true));
        when(transactions.transition(SCOPE, 19L, AgentHostedProfileState.PREPARED, 0L,
                AgentHostedProfileState.STAGED_DISABLED, 1L, true)).thenThrow(conflict);

        AgentPersonaProvisioningService service =
                new AgentPersonaProvisioningService(agentService, transactions, publisher);
        assertSame(conflict, assertThrows(AgentBizException.class,
                () -> service.bind(SCOPE, "wuyong", "server")));

        verify(transactions).markRepair(SCOPE, 19L, AgentHostedProfileState.PREPARED,
                null, 0L, AgentHostedProfileState.PREPARED, conflict);
    }

    @Test
    void exhaustedPreparedGenerationPerformsNoPublisherOrDurableMutationCalls() {
        AgentService agentService = mock(AgentService.class);
        AgentHostedBindingTransaction transactions = mock(AgentHostedBindingTransaction.class);
        AgentHostedProfilePublisher publisher = mock(AgentHostedProfilePublisher.class);
        AgentHostedProfileEntity exhausted = hosted(AgentHostedProfileState.PREPARED, Long.MAX_VALUE);
        when(transactions.prepareHosted(SCOPE, "wuyong"))
                .thenReturn(new Prepared(exhausted, runtime(), persona(), "dedicated-secret"));

        assertThrows(AgentBizException.class, () ->
                new AgentPersonaProvisioningService(agentService, transactions, publisher)
                        .bind(SCOPE, "wuyong", "server"));

        verifyNoInteractions(publisher);
        verify(transactions, never()).transition(any(), anyLong(), anyString(), anyLong(),
                anyString(), anyLong(), anyBoolean());
        verify(transactions, never()).markRepair(any(), anyLong(), anyString(),
                any(), anyLong(), anyString(), any());
    }

    private static AgentHostedProfilePublisher.PublishedPaths paths(
            boolean created, boolean alreadyExists) {
        return new AgentHostedProfilePublisher.PublishedPaths(
                "/configured/clients/" + AGENT_ID,
                "/configured/runtime/.codex-hosted-profile",
                "/configured/runtime/codex-profiles.conf",
                created, alreadyExists);
    }

    private static AgentHostedProfileEntity hosted(String state, long generation) {
        AgentHostedProfileEntity hosted = new AgentHostedProfileEntity();
        hosted.setId(7L);
        hosted.setBindingId(19L);
        hosted.setTenantId(SCOPE.tenantId());
        hosted.setClientId(SCOPE.clientId());
        hosted.setOwnerJiacn(SCOPE.ownerJiacn());
        hosted.setCanonicalAgentId(AGENT_ID);
        hosted.setPersonaCode("wuyong");
        hosted.setProfileKey(AgentHostedBindingTransaction.scopeDigest(SCOPE) + 19L);
        hosted.setApiKeyId("key-19");
        hosted.setLifecycleState(state);
        hosted.setGeneration(generation);
        hosted.setDesiredEnabled(!AgentHostedProfileState.SUSPENDING.equals(state));
        return hosted;
    }

    private static AgentRuntimeDTO runtime() {
        AgentRuntimeDTO runtime = new AgentRuntimeDTO();
        runtime.setAgentId(AGENT_ID);
        runtime.setName("Wu Yong");
        runtime.setPersonaName("Wu Yong");
        runtime.setTitle("Strategist");
        return runtime;
    }

    private static AgentPersonaEntity persona() {
        AgentPersonaEntity persona = new AgentPersonaEntity();
        persona.setPersonaCode("wuyong");
        persona.setName("Wu Yong");
        persona.setTitle("Strategist");
        return persona;
    }
}
