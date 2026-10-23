package cn.jia.chat.service;

import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.hosting.*;
import cn.jia.agent.security.*;
import cn.jia.agent.service.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentProfileCapabilitiesServiceTest {
    final AgentService agents = mock(AgentService.class);
    final HostingRentOwnerResolver owners = mock(HostingRentOwnerResolver.class);
    final AgentRuntimeAuthenticationService runtimes = mock(AgentRuntimeAuthenticationService.class);
    final ChatActionCapabilityService capabilities = mock(ChatActionCapabilityService.class);
    final HostingRentHttp.Actor actor = new HostingRentHttp.Actor("user", "0", "client", "owner");
    final ChatActionCapabilityService.Scope scope = new ChatActionCapabilityService.Scope("0", "owner", "client", "agent");
    final AgentProfileCapabilitiesService service = new AgentProfileCapabilitiesService(agents, owners, runtimes, capabilities,
            Clock.fixed(Instant.ofEpochMilli(10000), ZoneOffset.UTC));
    AgentRuntimeDTO agent;
    @BeforeEach void setup() {
        agent = new AgentRuntimeDTO(); agent.setAgentId("agent"); agent.setOwnerJiacn("owner");
        agent.setSystemAgent(false); agent.setAbilities(List.of("内容管理")); agent.setLastSeenAt(9000L);
        when(owners.requireOwner(actor)).thenReturn("owner");
        when(agents.requireApiKeyOwnedAgent("client", "owner", "agent")).thenReturn(agent);
        when(runtimes.currentRegisteredProof("0", "client", "agent")).thenThrow(new IllegalArgumentException("no proof"));
        when(capabilities.inspectInstalledSkillsWithoutRuntime(scope)).thenReturn(new ChatActionCapabilityService.InstalledCatalog(null, List.of()));
    }
    ChatActionCapabilityService.SkillCandidate candidate(boolean installed, String state, String reason) {
        return new ChatActionCapabilityService.SkillCandidate("image-edit", "1.0.0", "图片修改", state, reason, "private metadata", installed);
    }
    void offline(ChatActionCapabilityService.SkillCandidate... items) {
        when(capabilities.inspectInstalledSkillsWithoutRuntime(scope)).thenReturn(new ChatActionCapabilityService.InstalledCatalog(null, List.of(items)));
    }
    @Test void emptyAndLiteralEmptyAbilitiesAreAuthoritativeAndNoVersionOrTaskWriteOccurs() {
        agent.setAbilities(List.of()); var result = service.read(actor, "agent");
        assertTrue(result.abilities().isEmpty()); assertEquals(10000, result.observedAt());
        assertEquals("AVAILABLE", result.skills().status()); assertTrue(result.skills().items().isEmpty());
        verify(agents, times(2)).requireApiKeyOwnedAgent("client", "owner", "agent"); verifyNoMoreInteractions(agents);
        verify(capabilities).inspectInstalledSkillsWithoutRuntime(scope); verifyNoMoreInteractions(capabilities);
    }
    @Test void installationRemainsVerifiedWithoutRuntimeAndNoPrivateMetadataEscapes() {
        offline(candidate(true, "UNAVAILABLE", "SKILL_RUNTIME_UNAVAILABLE"));
        var item = service.read(actor, "agent").skills().items().getFirst();
        assertTrue(item.installationVerified()); assertEquals("UNAVAILABLE", item.runtimeState());
        assertEquals("图片修改", item.title()); assertEquals(6, item.getClass().getRecordComponents().length);
    }
    @Test void readyNeedsExactTrustedProofAndNoTaskGrant() {
        var proof = new AgentRuntimeAuthenticationService.Proof(new AgentRuntimeAuthentication.Scope("0", "client", "owner", "agent", "runtime"), "installation", "host", 1, "a".repeat(64), 1, 1);
        doReturn(proof).when(runtimes).currentRegisteredProof("0", "client", "agent");
        when(capabilities.inspectInstalledSkills(eq(scope), any())).thenReturn(new ChatActionCapabilityService.InstalledCatalog("999999999999999999999", List.of(candidate(true, "RUNTIME_READY", null))));
        var result = service.read(actor, "agent"); assertEquals("RUNTIME_READY", result.skills().items().getFirst().runtimeState());
        assertEquals("999999999999999999999", result.skills().catalogRevision());
        verify(capabilities, never()).taskCatalog(any(), any());
    }
    @Test void sourceFailureIsPartialNotEmptyDirectory() {
        when(capabilities.inspectInstalledSkillsWithoutRuntime(scope)).thenThrow(new InstalledSkillDescriptorLookup.SourceUnavailable());
        var result = service.read(actor, "agent"); assertEquals(List.of("内容管理"), result.abilities());
        assertEquals("UNAVAILABLE", result.skills().status()); assertEquals("SKILL_DESCRIPTOR_SOURCE_UNAVAILABLE", result.skills().reasonCode());
        verify(agents, times(2)).requireApiKeyOwnedAgent("client", "owner", "agent");
    }
    @Test void duplicateVersionsCollapseOnlyInProfile() {
        offline(candidate(true, "UNAVAILABLE", "SKILL_INSTALLATION_AMBIGUOUS"), candidate(true, "UNAVAILABLE", "SKILL_INSTALLATION_AMBIGUOUS"));
        var items = service.read(actor, "agent").skills().items(); assertEquals(1, items.size()); assertFalse(items.getFirst().installationVerified());
        assertEquals("image-edit", items.getFirst().title()); assertEquals("SKILL_INSTALLATION_AMBIGUOUS", items.getFirst().reasonCode());
    }
    @Test void unknownRuntimeReasonDoesNotEraseVerifiedInstallation() {
        offline(candidate(true, "UNAVAILABLE", "PROVIDER_NEW_REASON")); assertTrue(service.read(actor, "agent").skills().items().getFirst().installationVerified());
    }
    @Test void proofScopeMismatchAndObservationIllegalArgumentAreNotTreatedAsOffline() {
        doReturn(new AgentRuntimeAuthenticationService.Proof(new AgentRuntimeAuthentication.Scope("0", "client", "other-owner", "agent", "runtime"), "installation", "host", 1, "a".repeat(64), 1, 1)).when(runtimes).currentRegisteredProof("0", "client", "agent");
        assertThrows(IllegalStateException.class, () -> service.read(actor, "agent")); verifyNoInteractions(capabilities);
        doThrow(new IllegalArgumentException("missing")).when(runtimes).currentRegisteredProof("0", "client", "agent");
        when(capabilities.inspectInstalledSkillsWithoutRuntime(scope)).thenThrow(new IllegalArgumentException("bad directory"));
        assertThrows(IllegalArgumentException.class, () -> service.read(actor, "agent"));
    }
    @Test void ownerAndBindingAreRecheckedEvenOnPartialFailure() {
        when(agents.requireApiKeyOwnedAgent("client", "owner", "agent")).thenReturn(agent).thenThrow(new HostingRentApplicationException(404, "denied"));
        assertThrows(HostingRentApplicationException.class, () -> service.read(actor, "agent"));
    }
    @Test void unsafeTimesBadRevisionAndImpossibleReadyFailClosed() {
        agent.setLastSeenAt(9007199254740992L); assertThrows(IllegalStateException.class, () -> service.read(actor, "agent")); agent.setLastSeenAt(null);
        when(capabilities.inspectInstalledSkillsWithoutRuntime(scope)).thenReturn(new ChatActionCapabilityService.InstalledCatalog("01", List.of()));
        assertThrows(IllegalStateException.class, () -> service.read(actor, "agent"));
        offline(candidate(false, "RUNTIME_READY", null)); assertThrows(IllegalStateException.class, () -> service.read(actor, "agent"));
    }
    @Test void canonicalSystemAndOwnershipRejectBeforeReadingSkills() {
        agent.setAgentId("alias"); assertThrows(HostingRentApplicationException.class, () -> service.read(actor, "agent"));
        agent.setAgentId("agent"); agent.setSystemAgent(true); assertThrows(HostingRentApplicationException.class, () -> service.read(actor, "agent"));
        agent.setSystemAgent(false); agent.setOwnerJiacn("other"); assertThrows(HostingRentApplicationException.class, () -> service.read(actor, "agent")); verifyNoInteractions(capabilities);
    }
}
