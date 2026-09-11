package cn.jia.chat.service;

import cn.jia.chat.dao.AgentTaskThreadDao;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.AgentTaskThreadConstants;
import cn.jia.chat.entity.AgentTaskThreadEntity;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.exception.AgentTaskThreadException;
import cn.jia.chat.service.impl.ChatConversationServiceImpl;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatConversationTaskThreadGuardTest extends BaseMockTest {
    private static final String OWNER = "Owner-A";
    private static final String CLIENT = "Client-A";

    @Mock ChatConversationDao conversationDao;
    @Mock ChatMessageDao messageDao;
    @Mock AgentTaskThreadDao taskThreadDao;

    @BeforeEach
    void setUpIdentity() {
        EsContext context = new EsContext();
        context.setJiacn(OWNER);
        context.setClientId(CLIENT);
        EsContextHolder.setContext(context);
    }

    @AfterEach
    void clearIdentity() {
        EsContextHolder.clearContext();
    }

    @Test
    void maliciousListIdentityIsDiscardedAndAuthenticatedScopeIsByteExact() {
        ChatConversationEntity requested = new ChatConversationEntity()
                .setJiacn("attacker")
                .setConversationType("juyiting")
                .setDeletedAt(123L);
        requested.setTenantId("attacker-tenant");
        requested.setClientId("attacker-client");
        ChatConversationEntity owned = ownedConversation(41L, "0")
                .setConversationType("juyiting");
        when(conversationDao.selectNonTaskThreadByEntity(any())).thenReturn(List.of(owned));

        assertEquals(List.of(owned), service().findPage(requested, 1, 20, null).getList());

        ArgumentCaptor<ChatConversationEntity> captor =
                ArgumentCaptor.forClass(ChatConversationEntity.class);
        verify(conversationDao).selectNonTaskThreadByEntity(captor.capture());
        ChatConversationEntity safe = captor.getValue();
        assertEquals(OWNER, safe.getJiacn());
        assertEquals(OWNER, safe.getTenantId());
        assertEquals(CLIENT, safe.getClientId());
        assertEquals("juyiting", safe.getConversationType());
        assertNull(safe.getDeletedAt());
        assertEquals("attacker", requested.getJiacn(), "caller object must not be rewritten");
        assertEquals("attacker-client", requested.getClientId());
    }

    @Test
    void malformedOrMissingAuthenticatedIdentityFailsBeforeDao() {
        EsContextHolder.clearContext();
        assertUnavailable(() -> service().findPage(null, 1, 20, null));
        assertUnavailable(() -> service().get("41"));
        for (String owner : List.of("", " Owner-A", "Owner-A\0", "x".repeat(51))) {
            EsContext context = new EsContext();
            context.setJiacn(owner);
            context.setClientId(CLIENT);
            EsContextHolder.setContext(context);
            assertUnavailable(() -> service().findPage(null, 1, 20, null));
            assertUnavailable(() -> service().get("41"));
        }
        EsContext malformedClient = new EsContext();
        malformedClient.setJiacn(OWNER);
        malformedClient.setClientId(" Client-A");
        EsContextHolder.setContext(malformedClient);
        assertUnavailable(() -> service().findPage(null, 1, 20, null));
        assertUnavailable(() -> service().get("41"));
        verify(conversationDao, never()).selectNonTaskThreadByEntity(any());
        verify(conversationDao, never()).findScopedById(any(), any(), any());
    }

    @Test
    void genericReadRejectsTaskThreadAndCrossUserWithoutMessageQuery() {
        ChatConversationEntity taskThread = ownedConversation(77L, OWNER)
                .setConversationScopeType(AgentTaskThreadConstants.CONVERSATION_SCOPE_TYPE);
        when(conversationDao.findScopedById(OWNER, CLIENT, "77")).thenReturn(taskThread);
        assertUnavailable(() -> service().findByConversationId("77"));

        ChatConversationEntity bound = ownedConversation(78L, OWNER);
        when(conversationDao.findScopedById(OWNER, CLIENT, "78")).thenReturn(bound);
        when(taskThreadDao.findAnyByConversationId("78"))
                .thenReturn(new AgentTaskThreadEntity().setConversationId("78"));
        assertUnavailable(() -> service().findByConversationId("78"));

        when(conversationDao.findScopedById(OWNER, CLIENT, "79")).thenReturn(null);
        assertUnavailable(() -> service().findByConversationId("79"));
        verify(messageDao, never()).findOwnedByConversationId(any(), any(), any());
    }

    @Test
    void taskThreadEvidenceBlocksEveryGenericReadAndMutationPath() {
        ChatConversationEntity protectedConversation = ownedConversation(80L, OWNER)
                .setConversationScopeType(AgentTaskThreadConstants.CONVERSATION_SCOPE_TYPE);
        when(conversationDao.findScopedById(OWNER, CLIENT, "80"))
                .thenReturn(protectedConversation);
        when(conversationDao.lockScopedById(OWNER, CLIENT, "80"))
                .thenReturn(protectedConversation);
        when(conversationDao.lockScopedByIdIncludingDeleted(OWNER, CLIENT, "80"))
                .thenReturn(protectedConversation);
        ChatConversationServiceImpl service = service();

        assertUnavailable(() -> service.get("80"));
        assertUnavailable(() -> service.findByConversationId("80"));
        assertUnavailable(() -> service.update(new ChatConversationEntity().setId(80L)));
        service.deleteConversation("80");

        verify(messageDao, never()).findOwnedByConversationId(any(), any(), any());
        verify(messageDao, never()).deleteExactConversationMessages(any());
        verify(conversationDao, never()).softDeleteScopedById(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
        verify(conversationDao, never()).updateById(any());
    }

    @Test
    void contaminatedForeignConversationResultFailsClosedAndDeleteDoesNotDisclose() {
        ChatConversationEntity foreign = ownedConversation(81L, "Owner-B").setJiacn("Owner-B");
        foreign.setClientId("Client-B");
        when(conversationDao.findScopedById(OWNER, CLIENT, "81")).thenReturn(foreign);
        when(conversationDao.lockScopedById(OWNER, CLIENT, "81")).thenReturn(foreign);
        when(conversationDao.lockScopedByIdIncludingDeleted(OWNER, CLIENT, "81"))
                .thenReturn(foreign);
        ChatConversationServiceImpl service = service();

        assertUnavailable(() -> service.get("81"));
        assertUnavailable(() -> service.findByConversationId("81"));
        assertUnavailable(() -> service.update(new ChatConversationEntity().setId(81L)));
        service.deleteConversation("81");

        verify(messageDao, never()).findOwnedByConversationId(any(), any(), any());
        verify(messageDao, never()).deleteExactConversationMessages(any());
        verify(conversationDao, never()).softDeleteScopedById(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
        verify(conversationDao, never()).updateById(any());
    }

    @Test
    void ownedMessageReadUsesExactOwnerClientAndConversationScope() {
        ChatConversationEntity owned = ownedConversation(91L, "0");
        ChatMessageEntity message = new ChatMessageEntity().setConversationId("91")
                .setJiacn(OWNER).setContent("owned");
        message.setClientId(CLIENT);
        message.setTenantId("0");
        when(conversationDao.findScopedById(OWNER, CLIENT, "91")).thenReturn(owned);
        when(messageDao.findOwnedByConversationId(OWNER, CLIENT, "91"))
                .thenReturn(List.of(message));

        assertEquals(List.of(message), service().findByConversationId("91"));
    }

    @Test
    void repeatedDeleteConvergesAndDoesNotDiscloseMissingOrForeignRows() {
        ChatConversationEntity live = ownedConversation(92L, OWNER);
        ChatConversationEntity tombstoned = ownedConversation(92L, OWNER).setDeletedAt(999L);
        when(conversationDao.lockScopedByIdIncludingDeleted(OWNER, CLIENT, "92"))
                .thenReturn(live, tombstoned);
        when(conversationDao.softDeleteScopedById(
                org.mockito.ArgumentMatchers.eq(OWNER),
                org.mockito.ArgumentMatchers.eq(CLIENT),
                org.mockito.ArgumentMatchers.eq("92"),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);

        ChatConversationServiceImpl service = service();
        service.deleteConversation("92");
        service.deleteConversation("92");
        service.deleteConversation("404");

        verify(conversationDao, times(1)).softDeleteScopedById(
                org.mockito.ArgumentMatchers.eq(OWNER),
                org.mockito.ArgumentMatchers.eq(CLIENT),
                org.mockito.ArgumentMatchers.eq("92"),
                org.mockito.ArgumentMatchers.anyLong());
        verify(messageDao, times(1)).deleteExactConversationMessages("92");
    }

    @Test
    void deleteAndLateCallbackUseConversationThenMessageLockOrder() {
        ChatConversationEntity live = ownedConversation(93L, OWNER);
        when(conversationDao.lockScopedByIdIncludingDeleted(OWNER, CLIENT, "93"))
                .thenReturn(live);
        when(conversationDao.softDeleteScopedById(
                org.mockito.ArgumentMatchers.eq(OWNER),
                org.mockito.ArgumentMatchers.eq(CLIENT),
                org.mockito.ArgumentMatchers.eq("93"),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);
        ChatConversationServiceImpl service = service();

        service.deleteConversation("93");
        InOrder order = inOrder(conversationDao, messageDao);
        order.verify(conversationDao).lockScopedByIdIncludingDeleted(OWNER, CLIENT, "93");
        order.verify(conversationDao).softDeleteScopedById(
                org.mockito.ArgumentMatchers.eq(OWNER),
                org.mockito.ArgumentMatchers.eq(CLIENT),
                org.mockito.ArgumentMatchers.eq("93"),
                org.mockito.ArgumentMatchers.anyLong());
        order.verify(messageDao).deleteExactConversationMessages("93");

        when(conversationDao.lockScopedById(OWNER, CLIENT, "93")).thenReturn(null);
        ChatMessageEntity late = new ChatMessageEntity().setConversationId("93")
                .setMessageType("ASSISTANT").setContent("late");
        assertUnavailable(() -> service.appendOwnedMessage(OWNER, CLIENT, late));
        verify(messageDao, never()).insert(any(ChatMessageEntity.class));
    }

    @Test
    void appendDerivesEveryIdentityFieldFromLockedConversation() {
        ChatConversationEntity owned = ownedConversation(94L, "0")
                .setConversationType("juyiting");
        when(conversationDao.lockScopedById(OWNER, CLIENT, "94")).thenReturn(owned);
        when(messageDao.insert(any())).thenReturn(1);
        ChatMessageEntity attacker = new ChatMessageEntity()
                .setConversationId("94")
                .setJiacn("attacker")
                .setConversationType("normal")
                .setMessageType("USER")
                .setContent("hello");
        attacker.setTenantId("attacker");
        attacker.setClientId("attacker-client");

        ChatMessageEntity saved = service().appendOwnedMessage(OWNER, CLIENT, attacker);

        assertEquals(OWNER, saved.getJiacn());
        assertEquals(CLIENT, saved.getClientId());
        assertEquals("0", saved.getTenantId());
        assertEquals("juyiting", saved.getConversationType());
        assertEquals("94", saved.getConversationId());
        verify(messageDao).insert(saved);
    }

    @Test
    void currentTenantConversationRemainsAccessible() {
        ChatConversationEntity owned = ownedConversation(95L, OWNER);
        when(conversationDao.findScopedById(OWNER, CLIENT, "95")).thenReturn(owned);
        assertSame(owned, service().get("95"));
    }

    private ChatConversationServiceImpl service() {
        return new ChatConversationServiceImpl(conversationDao, messageDao, taskThreadDao);
    }

    private ChatConversationEntity ownedConversation(long id, String tenantId) {
        ChatConversationEntity conversation = new ChatConversationEntity()
                .setId(id).setJiacn(OWNER);
        conversation.setTenantId(tenantId);
        conversation.setClientId(CLIENT);
        return conversation;
    }

    private void assertUnavailable(ThrowingAction action) {
        AgentTaskThreadException denied = assertThrows(AgentTaskThreadException.class, action::run);
        assertEquals(AgentTaskThreadException.Reason.NOT_FOUND_OR_FORBIDDEN, denied.getReason());
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
