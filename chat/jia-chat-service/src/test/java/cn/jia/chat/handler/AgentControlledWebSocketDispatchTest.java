package cn.jia.chat.handler;

import cn.jia.agent.config.AgentTaskEventsGate;
import cn.jia.agent.config.AgentTaskEventsProperties;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentArchiveMaintenancePayload;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.entity.AgentPlatformSkillInstallPayload;
import cn.jia.agent.entity.AgentRawCommandDispatchResult;
import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRegisterResultDTO;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.impl.AgentCommandCanonicalCodec;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.service.ChatConversationEventBroker;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import cn.jia.user.security.AccountState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentControlledWebSocketDispatchTest {
    private static final String TENANT = "0";
    private static final String CLIENT = "client-a";
    private static final String OWNER = "owner-a";
    private static final String AGENT = "agt_" + "a".repeat(32);
    private static final String OTHER_AGENT = "agt_" + "b".repeat(32);
    private static final String TOKEN = "1".repeat(32);
    private static final String KEY_ID = "key-a";
    private static final long BINDING = 17L;

    @Test
    void canonicalPlatformAndArchiveCommandsReachOnlyTheExactAuthenticatedRegisteredSession()
            throws Exception {
        Fixture fixture = new Fixture();
        fixture.register();
        AgentRuntimeAuthenticationService.ControlledTarget platform = fixture.proof(
                BINDING, "PLATFORM_SKILL_INSTALL/v1");
        AgentRuntimeAuthenticationService.ControlledTarget archive = fixture.proof(
                BINDING, "ARCHIVE_MAINTENANCE_EXECUTE/v1");

        for (AgentCommandDraft draft : List.of(platformDraft(OWNER, CLIENT, AGENT, "17"),
                archiveDraft(OWNER, CLIENT, AGENT, "17"))) {
            byte[] wire = AgentCommandCanonicalCodec.wireBytes(draft, "message-" + draft.commandType(), 1);
            fixture.frames.clear();

            AgentRawCommandDispatchResult result = fixture.handler.dispatchManagedSkill(
                    TENANT, CLIENT, AGENT, KEY_ID,
                    "PLATFORM_SKILL_INSTALL".equals(draft.commandType())
                            ? platform.registrationHash() : archive.registrationHash(), wire);

            assertEquals(AgentRawCommandDispatchResult.Status.SENT, result.status());
            assertEquals(1, result.matchingSessionCount());
            assertEquals(1, result.sentSessionCount());
            assertEquals(1, fixture.frames.size());
            assertArrayEquals(wire, fixture.frames.getFirst().getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void controlledDispatchRejectsScopeProofProtocolAndCanonicalWireBypassesBeforeSend()
            throws Exception {
        Fixture fixture = new Fixture();
        fixture.register();
        AgentRuntimeAuthenticationService.ControlledTarget proof = fixture.proof(
                BINDING, "PLATFORM_SKILL_INSTALL/v1");
        byte[] good = wire(platformDraft(OWNER, CLIENT, AGENT, "17"));
        fixture.frames.clear();

        AgentRawCommandDispatchResult wrongTenant = fixture.handler.dispatchManagedSkill(
                "1", CLIENT, AGENT, KEY_ID, proof.registrationHash(), good);
        assertNotEquals(AgentRawCommandDispatchResult.Status.SENT, wrongTenant.status());
        assertTrue(fixture.frames.isEmpty());
        assertNotSent(fixture, CLIENT, AGENT, "other-key", proof.registrationHash(), good);
        assertNotSent(fixture, CLIENT, AGENT, KEY_ID, new byte[32], good);
        assertNotSent(fixture, CLIENT, AGENT, KEY_ID, proof.registrationHash(),
                wire(platformDraft("owner-b", CLIENT, AGENT, "17")));
        assertNotSent(fixture, CLIENT, AGENT, KEY_ID, proof.registrationHash(),
                wire(platformDraft(OWNER, CLIENT, AGENT, "18")));
        assertNotSent(fixture, CLIENT, AGENT, KEY_ID, proof.registrationHash(),
                wire(platformDraft(OWNER, "client-b", AGENT, "17")));
        assertNotSent(fixture, CLIENT, AGENT, KEY_ID, proof.registrationHash(),
                wire(platformDraft(OWNER, CLIENT, OTHER_AGENT, "17")));

        String json = new String(good, StandardCharsets.UTF_8);
        assertNotSent(fixture, CLIENT, AGENT, KEY_ID, proof.registrationHash(),
                json.replace("\"commandType\":\"PLATFORM_SKILL_INSTALL\"",
                        "\"commandType\":\"PLATFORM_SKILL\"").getBytes(StandardCharsets.UTF_8));
        assertNotSent(fixture, CLIENT, AGENT, KEY_ID, proof.registrationHash(),
                json.replace("\"messageId\":\"message-1\"",
                        "\"messageId\":\"message-1\",\"eventId\":\"event-1\"")
                        .getBytes(StandardCharsets.UTF_8));
        assertNotSent(fixture, CLIENT, AGENT, KEY_ID, proof.registrationHash(),
                json.replace("\"ownerJiacn\":\"owner-a\"",
                        "\"ownerJiacn\":\"owner-a\",\"ownerJiacn\":\"owner-a\"")
                        .getBytes(StandardCharsets.UTF_8));

        fixture.authentication.registerCommandProtocols("socket-a", AGENT,
                List.of("ARCHIVE_MAINTENANCE_EXECUTE/v1"));
        assertNotSent(fixture, CLIENT, AGENT, KEY_ID, proof.registrationHash(), good);
        fixture.authentication.registerCommandProtocols("socket-a", AGENT,
                List.of("PLATFORM_SKILL_INSTALL/v1", "ARCHIVE_MAINTENANCE_EXECUTE/v1"));
        org.mockito.Mockito.doThrow(new IllegalStateException("simulated runtime store outage"))
                .when(fixture.runtimes).findByAgentId(AGENT);
        assertThrows(IllegalStateException.class, () -> fixture.handler.dispatchManagedSkill(
                TENANT, CLIENT, AGENT, KEY_ID, proof.registrationHash(), good));
        org.mockito.Mockito.doReturn(fixture.runtime)
                .when(fixture.runtimes).findByAgentId(AGENT);
        ReplacementSession replacementSession = fixture.registerReplacementSession();
        AgentRuntimeAuthenticationService.ControlledTarget replacement = fixture.proof(
                BINDING, "PLATFORM_SKILL_INSTALL/v1");
        assertEquals("runtime-b", replacement.runtimeInstanceId());
        AgentRawCommandDispatchResult replacementResult = fixture.handler.dispatchManagedSkill(
                TENANT, CLIENT, AGENT, KEY_ID, replacement.registrationHash(), good);
        assertEquals(AgentRawCommandDispatchResult.Status.SENT, replacementResult.status());
        assertEquals(1, replacementResult.matchingSessionCount());
        assertEquals(1, replacementResult.sentSessionCount());
        assertTrue(fixture.frames.isEmpty());
        assertEquals(1, replacementSession.frames().size());
        assertArrayEquals(good, replacementSession.frames().getFirst()
                .getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void controlledDispatchNeverSendsInsideAnOpenTransaction() throws Exception {
        Fixture fixture = new Fixture();
        fixture.register();
        AgentRuntimeAuthenticationService.ControlledTarget proof = fixture.proof(
                BINDING, "ARCHIVE_MAINTENANCE_EXECUTE/v1");
        byte[] wire = wire(archiveDraft(OWNER, CLIENT, AGENT, "17"));
        fixture.frames.clear();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> fixture.handler.dispatchManagedSkill(
                    TENANT, CLIENT, AGENT, KEY_ID, proof.registrationHash(), wire));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertTrue(fixture.frames.isEmpty());
    }

    private static void assertNotSent(Fixture fixture, String client, String agent, String key,
            byte[] generation, byte[] wire) {
        AgentRawCommandDispatchResult result = fixture.handler.dispatchManagedSkill(
                TENANT, client, agent, key, generation, wire);
        assertNotEquals(AgentRawCommandDispatchResult.Status.SENT, result.status());
        assertTrue(fixture.frames.isEmpty());
    }

    private static byte[] wire(AgentCommandDraft draft) {
        return AgentCommandCanonicalCodec.wireBytes(draft, "message-1", 1);
    }

    private static AgentCommandDraft platformDraft(
            String owner, String client, String agent, String binding) {
        AgentPlatformSkillInstallPayload payload = new AgentPlatformSkillInstallPayload(
                1, "installation-1", binding, "archive-maintainer", "1.0.0",
                "a".repeat(64), "challenge-1",
                "/internal/agent/platform-skills/installations/installation-1/package");
        return controlledDraft(owner, client, agent, "PLATFORM_SKILL_INSTALL",
                "installation-1", "installation-1", "challenge-1", payload);
    }

    private static AgentCommandDraft archiveDraft(
            String owner, String client, String agent, String binding) {
        AgentArchiveMaintenancePayload payload = new AgentArchiveMaintenancePayload(
                1, "job-1", "run-1", "2", "appointment-1", "3", "4", binding,
                "grant-1", "execution-1", "dispatch-1", "installation-1",
                "a".repeat(64), "/internal/archive/v1/jobs/job-1/runs/run-1/context");
        return controlledDraft(owner, client, agent, "ARCHIVE_MAINTENANCE_EXECUTE",
                "run-1", "job-1", "run-1", payload);
    }

    private static AgentCommandDraft controlledDraft(String owner, String client, String agent,
            String type, String resource, String task, String cause,
            cn.jia.agent.entity.AgentCommandPayload payload) {
        return new AgentCommandDraft(1,
                AgentCommandCanonicalCodec.controlledCommandId(
                        TENANT, client, owner, resource, agent, type),
                task, cause, TENANT, client, owner, task, null, agent, type,
                1L, 3_600_001L, payload);
    }

    private static final class Fixture {
        private final AgentRuntimeDao runtimes = mock(AgentRuntimeDao.class);
        private final AgentIdentityService identities = mock(AgentIdentityService.class);
        private final ApiKeyService keys = mock(ApiKeyService.class);
        private final AccountSecurityService accounts = mock(AccountSecurityService.class);
        private final AgentService agents = mock(AgentService.class);
        private final WebSocketSession session = mock(WebSocketSession.class);
        private final AtomicBoolean connected = new AtomicBoolean(true);
        private final Map<String, Object> attributes = new HashMap<>();
        private final List<String> frames = new ArrayList<>();
        private final AgentRuntimeEntity runtime;
        private final AgentRuntimeAuthenticationService authentication;
        private final AgentWebSocketHandler handler;

        @SuppressWarnings("unchecked")
        private Fixture() throws Exception {
            runtime = new AgentRuntimeEntity()
                    .setAgentId(AGENT).setOwnerJiacn(OWNER).setBindingId(BINDING)
                    .setTokenHash(TOKEN).setStatus("online");
            runtime.setTenantId(TENANT);
            runtime.setClientId(CLIENT);
            when(runtimes.findByAgentId(AGENT)).thenReturn(runtime);
            AgentIdentityRegistryEntity identity = new AgentIdentityRegistryEntity()
                    .setCanonicalAgentId(AGENT);
            when(identities.requireActiveIdentityForBinding(
                    TENANT, CLIENT, OWNER, BINDING, AGENT)).thenReturn(identity);
            when(identities.requireActiveBinding(identity, null))
                    .thenReturn(new AgentPersonaBindingEntity().setId(BINDING));
            OauthApiKeyEntity key = new OauthApiKeyEntity().setId(KEY_ID)
                    .setApiKey("fixture-api-key").setJiacn(OWNER).setStatus(1);
            key.setTenantId(TENANT);
            key.setClientId(CLIENT);
            when(keys.get(KEY_ID)).thenReturn(key);
            when(accounts.findUniqueByExactJiacn(OWNER)).thenReturn(Optional.of(
                    new AccountSecuritySnapshot(1, OWNER, AccountState.ACTIVE, 0)));
            authentication = new AgentRuntimeAuthenticationService(
                    runtimes, identities, keys, accounts,
                    new AgentTaskEventsGate(new AgentTaskEventsProperties(false, List.of())));

            when(agents.register(any(AgentRegisterDTO.class))).thenReturn(
                    new AgentRegisterResultDTO(AGENT, TOKEN, "online"));
            ObjectProvider<AgentService> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(agents);
            attributes.put("agentId", AGENT);
            attributes.put("jiacn", OWNER);
            attributes.put("clientId", CLIENT);
            attributes.put("runtimeInstanceId", "runtime-a");
            attributes.put("managedApiKeyId", KEY_ID);
            when(session.getAttributes()).thenReturn(attributes);
            when(session.getId()).thenReturn("socket-a");
            when(session.isOpen()).thenAnswer(ignored -> connected.get());
            org.mockito.Mockito.doAnswer(invocation -> {
                frames.add(((TextMessage) invocation.getArgument(0)).getPayload());
                return null;
            }).when(session).sendMessage(any());
            handler = new AgentWebSocketHandler(mock(ChatClient.class), provider,
                    mock(ChatMessageDao.class), mock(ChatConversationEventBroker.class));
            handler.setRuntimeAuthentication(authentication);
            handler.afterConnectionEstablished(session);
        }

        private void register() {
            handler.handleTextMessage(session, new TextMessage("""
                    {"schemaVersion":1,"messageType":"agent.register","messageId":"register-1",
                     "agentId":"%s","runtimeInstanceId":"runtime-a","name":"Native",
                     "commandProtocols":["PLATFORM_SKILL_INSTALL/v1","ARCHIVE_MAINTENANCE_EXECUTE/v1"]}
                    """.formatted(AGENT)));
            assertTrue(frames.stream().anyMatch(frame -> frame.contains("agent_registered")));
            frames.clear();
        }

        private AgentRuntimeAuthenticationService.ControlledTarget proof(
                long binding, String protocol) {
            return authentication.requireControlledTarget(
                    TENANT, CLIENT, OWNER, AGENT, binding, protocol);
        }

        private ReplacementSession registerReplacementSession() throws Exception {
            WebSocketSession replacement = mock(WebSocketSession.class);
            Map<String, Object> replacementAttributes = new HashMap<>();
            List<String> replacementFrames = new ArrayList<>();
            replacementAttributes.put("agentId", AGENT);
            replacementAttributes.put("jiacn", OWNER);
            replacementAttributes.put("clientId", CLIENT);
            replacementAttributes.put("runtimeInstanceId", "runtime-b");
            replacementAttributes.put("managedApiKeyId", KEY_ID);
            when(replacement.getAttributes()).thenReturn(replacementAttributes);
            when(replacement.getId()).thenReturn("socket-b");
            when(replacement.isOpen()).thenReturn(true);
            org.mockito.Mockito.doAnswer(invocation -> {
                replacementFrames.add(((TextMessage) invocation.getArgument(0)).getPayload());
                return null;
            }).when(replacement).sendMessage(any());
            handler.afterConnectionEstablished(replacement);
            handler.handleTextMessage(replacement, new TextMessage("""
                    {"schemaVersion":1,"messageType":"agent.register","messageId":"register-2",
                     "agentId":"%s","runtimeInstanceId":"runtime-b","name":"Native replacement",
                     "commandProtocols":["PLATFORM_SKILL_INSTALL/v1","ARCHIVE_MAINTENANCE_EXECUTE/v1"]}
                    """.formatted(AGENT)));
            assertTrue(replacementFrames.stream()
                    .anyMatch(frame -> frame.contains("agent_registered")));
            replacementFrames.clear();
            return new ReplacementSession(replacement, replacementFrames);
        }
    }

    private record ReplacementSession(WebSocketSession session, List<String> frames) { }
}
