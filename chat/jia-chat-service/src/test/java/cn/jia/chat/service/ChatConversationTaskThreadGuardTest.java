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
import org.mockito.Mock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatConversationTaskThreadGuardTest extends BaseMockTest {
    private static final String OWNER = "owner-a";
    private static final String CLIENT = "client-a";

    @Mock
    ChatConversationDao conversationDao;
    @Mock
    ChatMessageDao messageDao;
    @Mock
    AgentTaskThreadDao taskThreadDao;

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
    void genericReadUpdateAndDeleteCannotBypassTaskThreadAcl() {
        ChatConversationEntity protectedConversation = ownedConversation(77L, OWNER);
        protectedConversation.setConversationScopeType(
                AgentTaskThreadConstants.CONVERSATION_SCOPE_TYPE);
        when(conversationDao.findScopedById(OWNER, CLIENT, "77"))
                .thenReturn(protectedConversation);
        ChatConversationServiceImpl service = service();

        assertAllGenericOperationsDenied(service, 77L);

        verify(messageDao, never()).findByConversationId("77");
        verify(messageDao, never()).deleteByConversationId("77");
        verify(conversationDao, never()).deleteById(77L);
        verify(conversationDao, never()).updateById(any());
    }

    @Test
    void bindingBlocksGenericApiEvenWhenConversationMarkerIsMissing() {
        ChatConversationEntity partiallyMigrated = ownedConversation(78L, OWNER);
        when(conversationDao.findScopedById(OWNER, CLIENT, "78"))
                .thenReturn(partiallyMigrated);
        when(taskThreadDao.findAnyByConversationId("78"))
                .thenReturn(new AgentTaskThreadEntity().setConversationId("78"));
        ChatConversationServiceImpl service = service();

        assertAllGenericOperationsDenied(service, 78L);

        verify(messageDao, never()).findByConversationId("78");
        verify(messageDao, never()).deleteByConversationId("78");
        verify(conversationDao, never()).deleteById(78L);
        verify(conversationDao, never()).updateById(any());
    }

    @Test
    void corruptedCaseAndNulTaskMarkersFailClosedWithoutBinding() {
        ChatConversationServiceImpl service = service();
        for (String marker : List.of("TASK_THREAD", "task_thread\0", " task_thread ")) {
            ChatConversationEntity corrupted = ownedConversation(79L, OWNER);
            corrupted.setConversationScopeType(marker);
            when(conversationDao.findScopedById(OWNER, CLIENT, "79"))
                    .thenReturn(corrupted);
            assertUnavailable(() -> service.get("79"));
        }
    }

    @Test
    void missingConversationFailsClosedForGetContentUpdateAndDelete() {
        ChatConversationServiceImpl service = service();

        assertAllGenericOperationsDenied(service, 80L);

        verify(conversationDao, times(4)).findScopedById(OWNER, CLIENT, "80");
        verify(taskThreadDao, never()).findAnyByConversationId(any());
        verify(messageDao, never()).findByConversationId(any());
        verify(messageDao, never()).deleteByConversationId(any());
        verify(conversationDao, never()).deleteById(any());
        verify(conversationDao, never()).updateById(any());
    }

    @Test
    void crossUserConversationFailsClosedForGetContentUpdateAndDelete() {
        ChatConversationEntity foreignUser = ownedConversation(81L, "owner-b")
                .setJiacn("owner-b");
        when(conversationDao.findScopedById(OWNER, CLIENT, "81"))
                .thenReturn(foreignUser);
        ChatConversationServiceImpl service = service();

        assertAllGenericOperationsDenied(service, 81L);

        verify(taskThreadDao, never()).findAnyByConversationId(any());
        verifyNoGenericReadOrMutation(81L);
    }

    @Test
    void crossClientConversationFailsClosedForGetContentUpdateAndDelete() {
        ChatConversationEntity foreignClient = ownedConversation(82L, "0");
        foreignClient.setClientId("client-b");
        when(conversationDao.findScopedById(OWNER, CLIENT, "82"))
                .thenReturn(foreignClient);
        ChatConversationServiceImpl service = service();

        assertAllGenericOperationsDenied(service, 82L);

        verify(taskThreadDao, never()).findAnyByConversationId(any());
        verifyNoGenericReadOrMutation(82L);
    }

    @Test
    void crossTenantConversationFailsClosedForGetContentUpdateAndDelete() {
        ChatConversationEntity foreignTenant = ownedConversation(84L, "owner-b");
        when(conversationDao.findScopedById(OWNER, CLIENT, "84"))
                .thenReturn(foreignTenant);
        ChatConversationServiceImpl service = service();

        assertAllGenericOperationsDenied(service, 84L);

        verify(taskThreadDao, never()).findAnyByConversationId(any());
        verifyNoGenericReadOrMutation(84L);
    }

    @Test
    void currentTenantConversationRemainsAccessible() {
        ChatConversationEntity owned = ownedConversation(85L, OWNER);
        when(conversationDao.findScopedById(OWNER, CLIENT, "85")).thenReturn(owned);

        assertSame(owned, service().get("85"));
    }

    @Test
    void missingRequestIdentityFailsBeforeConversationLookup() {
        EsContextHolder.setContext(new EsContext());
        ChatConversationServiceImpl service = service();

        assertUnavailable(() -> service.get("83"));

        verify(conversationDao, never()).findScopedById(any(), any(), any());
        verify(taskThreadDao, never()).findAnyByConversationId(any());
    }

    @Test
    void currentUserCanGetReadUpdateAndDeletePublicTenantConversation() {
        ChatConversationEntity owned = ownedConversation(91L, "0")
                .setTitle("old title")
                .setStatus(0)
                .setConversationType("normal");
        ChatMessageEntity message = new ChatMessageEntity()
                .setConversationId("91")
                .setJiacn(OWNER)
                .setContent("owned content");
        message.setClientId(CLIENT);
        when(conversationDao.findScopedById(OWNER, CLIENT, "91"))
                .thenReturn(owned);
        when(messageDao.findByConversationId("91")).thenReturn(List.of(message));
        when(conversationDao.deleteById(91L)).thenReturn(1);
        ChatConversationServiceImpl service = service();

        assertSame(owned, service.get("91"));
        assertEquals(List.of(message), service.findByConversationId("91"));

        ChatConversationEntity requestedUpdate = new ChatConversationEntity()
                .setId(91L)
                .setTitle("new title")
                .setStatus(1)
                .setJiacn("attacker")
                .setConversationType("juyiting")
                .setConversationScopeType(AgentTaskThreadConstants.CONVERSATION_SCOPE_TYPE)
                .setTaskId("foreign-task")
                .setTargetAgentId("foreign-agent");
        requestedUpdate.setTenantId("attacker");
        requestedUpdate.setClientId("client-b");
        ChatConversationEntity updated = service.update(requestedUpdate);

        ArgumentCaptor<ChatConversationEntity> updateCaptor =
                ArgumentCaptor.forClass(ChatConversationEntity.class);
        verify(conversationDao).updateById(updateCaptor.capture());
        ChatConversationEntity persisted = updateCaptor.getValue();
        assertSame(updated, persisted);
        assertEquals("new title", persisted.getTitle());
        assertEquals(1, persisted.getStatus());
        assertEquals(OWNER, persisted.getJiacn());
        assertEquals("0", persisted.getTenantId());
        assertEquals(CLIENT, persisted.getClientId());
        assertEquals("normal", persisted.getConversationType());
        assertNull(persisted.getConversationScopeType());
        assertNull(persisted.getTaskId());
        assertNull(persisted.getTargetAgentId());

        service.deleteConversation("91");

        verify(conversationDao).deleteById(91L);
        verify(messageDao).deleteByConversationId("91");
        verify(conversationDao, times(4)).findScopedById(OWNER, CLIENT, "91");
        verify(conversationDao, never()).selectByEntity(any());
    }

    @Test
    void deleteDoesNotRemoveMessagesWhenAuthorizedConversationDeleteLosesRace() {
        ChatConversationEntity owned = ownedConversation(92L, OWNER);
        when(conversationDao.findScopedById(OWNER, CLIENT, "92")).thenReturn(owned);
        when(conversationDao.deleteById(92L)).thenReturn(0);
        ChatConversationServiceImpl service = service();

        assertUnavailable(() -> service.deleteConversation("92"));

        verify(messageDao, never()).deleteByConversationId(any());
    }

    private ChatConversationServiceImpl service() {
        return new ChatConversationServiceImpl(conversationDao, messageDao, taskThreadDao);
    }

    private ChatConversationEntity ownedConversation(long id, String tenantId) {
        ChatConversationEntity conversation = new ChatConversationEntity()
                .setId(id)
                .setJiacn(OWNER);
        conversation.setTenantId(tenantId);
        conversation.setClientId(CLIENT);
        return conversation;
    }

    private void assertAllGenericOperationsDenied(ChatConversationServiceImpl service, long id) {
        String conversationId = Long.toString(id);
        assertUnavailable(() -> service.get(conversationId));
        assertUnavailable(() -> service.findByConversationId(conversationId));
        assertUnavailable(() -> service.update(new ChatConversationEntity().setId(id).setTitle("blocked")));
        assertUnavailable(() -> service.deleteConversation(conversationId));
    }

    private void assertUnavailable(ThrowingAction action) {
        AgentTaskThreadException denied = assertThrows(AgentTaskThreadException.class, action::run);
        assertEquals(AgentTaskThreadException.Reason.NOT_FOUND_OR_FORBIDDEN, denied.getReason());
    }

    private void verifyNoGenericReadOrMutation(long id) {
        String conversationId = Long.toString(id);
        verify(messageDao, never()).findByConversationId(conversationId);
        verify(messageDao, never()).deleteByConversationId(conversationId);
        verify(conversationDao, never()).deleteById(id);
        verify(conversationDao, never()).updateById(any());
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
