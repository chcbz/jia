package cn.jia.chat.service.impl;

import cn.jia.chat.dao.AgentTaskThreadDao;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.AgentTaskThreadConstants;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.chat.exception.AgentTaskThreadException;
import cn.jia.chat.service.ChatConversationEventBroker;
import cn.jia.chat.service.ConversationAdmissionService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.UserAdmissionScope;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatConversationScopedServiceTest extends BaseMockTest {
    @Mock ChatConversationDao conversationDao;
    @Mock ChatMessageDao messageDao;
    @Mock AgentTaskThreadDao taskThreadDao;
    @Mock ConversationAdmissionService admissionService;

    private ChatConversationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ChatConversationServiceImpl(
                conversationDao, messageDao, taskThreadDao,
                new ChatConversationEventBroker(),
                org.mockito.Mockito.mock(cn.jia.chat.deliberation.ChatBountyBindingStore.class),
                org.mockito.Mockito.mock(cn.jia.agent.service.AgentService.class));
        service.setConversationAdmissionService(admissionService);
    }

    @AfterEach
    void clearIdentity() {
        EsContextHolder.clearContext();
    }

    @Test
    void domainReadAllowsAnotherGrantedCallerClientButKeepsExactTenantDomainOwner() {
        UserAdmissionScope callerB = area("1", "cyf", "owner", "client-b");
        ConversationDataScope scope = admitRead(callerB, ConversationDataScope.Mode.DOMAIN);
        ChatConversationEntity shared = conversation(
                41L, "1", "cyf", "owner", "client-a", 1L);
        when(conversationDao.findScopedById(scope, "41")).thenReturn(shared);

        assertEquals(shared, service.get(callerB, "41"));

        verify(conversationDao).findScopedById(scope, "41");
    }

    @Test
    void domainReadRejectsEveryContaminatedAuthorityDimension() {
        UserAdmissionScope admission = area("1", "cyf", "owner", "client-b");
        ConversationDataScope scope = admitRead(admission, ConversationDataScope.Mode.DOMAIN);
        when(conversationDao.findScopedById(scope, "51")).thenReturn(
                conversation(51L, "0", "cyf", "owner", "client-a", 1L));
        when(conversationDao.findScopedById(scope, "52")).thenReturn(
                conversation(52L, "1", "other", "owner", "client-a", 1L));
        when(conversationDao.findScopedById(scope, "53")).thenReturn(
                conversation(53L, "1", "cyf", "foreign", "client-a", 1L));

        assertUnavailable(() -> service.get(admission, "51"));
        assertUnavailable(() -> service.get(admission, "52"));
        assertUnavailable(() -> service.get(admission, "53"));
    }

    @Test
    void legacyScopeStillRequiresExactCallerClient() {
        UserAdmissionScope admission = area("0", "cyf", "owner", "client-b");
        ConversationDataScope scope = admitRead(admission, ConversationDataScope.Mode.LEGACY_CLIENT);
        when(conversationDao.findScopedById(scope, "61")).thenReturn(
                conversation(61L, "0", "cyf", "owner", "client-a", 1L));

        assertUnavailable(() -> service.get(admission, "61"));
    }

    @Test
    void createAndAppendDeriveAllAuthorityFieldsFromAdmittedScope() {
        UserAdmissionScope callerB = area("1", "cyf", "owner", "client-b");
        ConversationDataScope scope = admitWrite(callerB, ConversationDataScope.Mode.DOMAIN);
        when(conversationDao.insert(any(ChatConversationEntity.class))).thenReturn(1);
        ChatConversationEntity forged = new ChatConversationEntity()
                .setTitle("new")
                .setJiacn("attacker")
                .setDataDomainId("attacker-domain")
                .setConversationType("juyiting")
                .setLifecycleGeneration(99L);
        forged.setTenantId("attacker-tenant");
        forged.setClientId("attacker-client");

        ChatConversationEntity created = service.create(callerB, forged);

        assertEquals("1", created.getTenantId());
        assertEquals("cyf", created.getDataDomainId());
        assertEquals("owner", created.getJiacn());
        assertEquals("client-b", created.getClientId());
        assertEquals(1L, created.getLifecycleGeneration());
        assertEquals("DOMAIN", created.getDataScopeMode());

        ChatConversationEntity existing = conversation(
                71L, "1", "cyf", "owner", "client-a", 3L)
                .setConversationType("juyiting");
        when(conversationDao.lockScopedById(scope, "71")).thenReturn(existing);
        when(messageDao.insert(any(ChatMessageEntity.class))).thenReturn(1);
        ChatMessageEntity forgedMessage = new ChatMessageEntity()
                .setConversationId("71")
                .setJiacn("attacker")
                .setDataDomainId("attacker-domain")
                .setConversationType("normal")
                .setMessageType("USER")
                .setContent("hello");
        forgedMessage.setTenantId("attacker-tenant");
        forgedMessage.setClientId("attacker-client");

        ChatMessageEntity appended = service.appendOwnedMessage(callerB, forgedMessage, 3L);

        assertEquals("1", appended.getTenantId());
        assertEquals("cyf", appended.getDataDomainId());
        assertEquals("owner", appended.getJiacn());
        assertEquals("client-b", appended.getClientId());
        assertEquals("juyiting", appended.getConversationType());
        assertEquals("71", appended.getConversationId());
        verify(messageDao).insert(appended);
    }

    @Test
    void legacyCreateAndScopedUpdatePersistTheAdmittedModeExplicitly() {
        EsContext identity = new EsContext();
        identity.setJiacn("owner");
        identity.setClientId("legacy-client");
        EsContextHolder.setContext(identity);
        when(conversationDao.insert(any(ChatConversationEntity.class))).thenReturn(1);

        ChatConversationEntity legacy = service.create(
                new ChatConversationEntity().setTitle("legacy"));

        assertEquals("0", legacy.getTenantId());
        assertEquals("cyf", legacy.getDataDomainId());
        assertEquals("LEGACY_CLIENT", legacy.getDataScopeMode());

        UserAdmissionScope admission = area("1", "cyf", "owner", "client-b");
        ConversationDataScope scope = admitWrite(admission, ConversationDataScope.Mode.DOMAIN);
        ChatConversationEntity existing = conversation(
                72L, "1", "cyf", "owner", "client-a", 2L);
        when(conversationDao.lockScopedById(scope, "72")).thenReturn(existing);
        when(conversationDao.updateScopedFields(eq(scope), any(ChatConversationEntity.class)))
                .thenReturn(1);

        ChatConversationEntity updated = service.updateOwnedTitle(
                admission, "72", "updated", 1);

        assertEquals("DOMAIN", updated.getDataScopeMode());
        ArgumentCaptor<ChatConversationEntity> update =
                ArgumentCaptor.forClass(ChatConversationEntity.class);
        verify(conversationDao).updateScopedFields(eq(scope), update.capture());
        assertEquals("DOMAIN", update.getValue().getDataScopeMode());
    }

    @Test
    void taskThreadMarkersAndStaleGenerationsNeverPersist() {
        UserAdmissionScope admission = area("1", "cyf", "owner", "client-b");
        ConversationDataScope scope = admitWrite(admission, ConversationDataScope.Mode.DOMAIN);
        ChatConversationEntity taskThread = new ChatConversationEntity()
                .setTitle("spoof")
                .setConversationScopeType(AgentTaskThreadConstants.CONVERSATION_SCOPE_TYPE);

        assertUnavailable(() -> service.create(admission, taskThread));
        verify(conversationDao, never()).insert(any(ChatConversationEntity.class));

        ChatConversationEntity existing = conversation(
                81L, "1", "cyf", "owner", "client-a", 4L);
        when(conversationDao.lockScopedById(scope, "81")).thenReturn(existing);
        ChatMessageEntity stale = new ChatMessageEntity()
                .setConversationId("81")
                .setMessageType("ASSISTANT")
                .setContent("late");

        assertUnavailable(() -> service.appendOwnedMessage(admission, stale, 3L));
        verify(messageDao, never()).insert(any(ChatMessageEntity.class));
    }

    @Test
    void admissionFailureRunsBeforeAnyConversationDaoCallback() {
        UserAdmissionScope admission = area("1", "cyf", "owner", "client-b");
        when(admissionService.read(eq(admission), any()))
                .thenThrow(new IllegalStateException("membership revoked"));

        assertThrows(IllegalStateException.class, () -> service.get(admission, "91"));

        verify(conversationDao, never()).findScopedById(any(ConversationDataScope.class), any());
        verify(conversationDao, never()).lockScopedById(any(ConversationDataScope.class), any());
    }

    private ConversationDataScope admitRead(
            UserAdmissionScope admission, ConversationDataScope.Mode mode) {
        ConversationDataScope scope = new ConversationDataScope(admission.businessScope(), mode);
        doAnswer(invocation -> applyWork(invocation.getArgument(1), scope))
                .when(admissionService).read(eq(admission), any());
        return scope;
    }

    private ConversationDataScope admitWrite(
            UserAdmissionScope admission, ConversationDataScope.Mode mode) {
        ConversationDataScope scope = new ConversationDataScope(admission.businessScope(), mode);
        doAnswer(invocation -> applyWork(invocation.getArgument(1), scope))
                .when(admissionService).write(eq(admission), any());
        return scope;
    }

    @SuppressWarnings("unchecked")
    private static Object applyWork(Object callback, ConversationDataScope scope) {
        return ((Function<ConversationDataScope, Object>) callback).apply(scope);
    }

    private static ChatConversationEntity conversation(
            long id, String tenant, String domain, String owner,
            String client, long generation) {
        ChatConversationEntity value = new ChatConversationEntity()
                .setId(id)
                .setJiacn(owner)
                .setDataDomainId(domain)
                .setLifecycleGeneration(generation);
        value.setTenantId(tenant);
        value.setClientId(client);
        value.setDataScopeMode("0".equals(tenant) ? "LEGACY_CLIENT" : "DOMAIN");
        value.setDataScopeMode("0".equals(tenant) ? "LEGACY_CLIENT" : "DOMAIN");
        return value;
    }

    private static UserAdmissionScope area(
            String tenant, String domain, String owner, String client) {
        return new UserAdmissionScope(new BusinessScope(
                TenantId.of(tenant), DataDomainId.of(domain), owner, client),
                9L, 4L, 2L, 5L, 8L);
    }

    private static void assertUnavailable(ThrowingAction action) {
        AgentTaskThreadException denied = assertThrows(
                AgentTaskThreadException.class, action::run);
        assertEquals(AgentTaskThreadException.Reason.NOT_FOUND_OR_FORBIDDEN,
                denied.getReason());
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
