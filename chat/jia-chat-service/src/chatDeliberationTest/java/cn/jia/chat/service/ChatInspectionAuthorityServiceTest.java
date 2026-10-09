package cn.jia.chat.service;

import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.PersonalWorkspaceStorage;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatRequestEntity;
import cn.jia.chat.deliberation.ChatTurnEntity;
import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import cn.jia.chat.handler.TypedInspectionSessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatInspectionAuthorityServiceTest {
    private final AgentTaskMutationTransaction tasks = mock(AgentTaskMutationTransaction.class);
    private final ChatBountyBindingStore bindings = mock(ChatBountyBindingStore.class);
    private final ChatConversationDao conversations = mock(ChatConversationDao.class);
    private final ChatDeliberationDao deliberation = mock(ChatDeliberationDao.class);
    private final ChatTypedDeliberationStore typed = mock(ChatTypedDeliberationStore.class);
    private final TypedInspectionSessionRegistry sessions = mock(TypedInspectionSessionRegistry.class);
    private final JuyitingConversationScopeService scopes = mock(JuyitingConversationScopeService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PersonalWorkspaceStorage storage = mock(PersonalWorkspaceStorage.class);
    private final PersonalWorkspaceExecutionService executions = mock(PersonalWorkspaceExecutionService.class);
    private final ChatConversationArchiveStore archive = mock(ChatConversationArchiveStore.class);
    private final ChatInspectionAuthorityService service = new ChatInspectionAuthorityService(tasks, bindings,
            conversations, deliberation, typed, sessions, scopes, jdbc, storage, executions, archive);

    @Test
    void foreignAgentAndCrossRequestTurnFailBeforeAnyAuthorityLockOrStorageRead() {
        ChatRequestEntity request = new ChatRequestEntity().setRequestId("request-1")
                .setRequestRevision(1L).setConversationId("42").setConversationGeneration(1L);
        ChatTurnEntity turn = new ChatTurnEntity().setTurnId("turn-1").setRequestId("request-1")
                .setRequestRevision(1L).setConversationId("42").setConversationGeneration(1L)
                .setTargetAgentId("agent-current").setRoute("INSPECT");
        when(deliberation.findRequest("0", "owner", "client", "request-1")).thenReturn(request);
        when(deliberation.findTurn("0", "owner", "client", "turn-1")).thenReturn(turn);
        ChatDeliberationException foreign = assertThrows(ChatDeliberationException.class,
                () -> service.read(runtime("agent-foreign"), "request-1", "turn-1", "source_1", digest()));
        assertEquals(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN, foreign.reason());

        turn.setRequestId("request-other");
        ChatDeliberationException crossed = assertThrows(ChatDeliberationException.class,
                () -> service.read(runtime("agent-current"), "request-1", "turn-1", "source_1", digest()));
        assertEquals(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN, crossed.reason());
        verifyNoInteractions(tasks);
        verify(storage, never()).read(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void contentAndFinalRecheckBindTheExactInputRoleUnderTheOwnerTaskLock() {
        var scope = new ChatTypedDeliberationStore.Scope("0", "owner", "client", "42", 1);
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Object[].class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    org.junit.jupiter.api.Assertions.assertTrue(sql.contains("l.link_role=? AND BINARY l.link_role=BINARY ?"));
                    org.junit.jupiter.api.Assertions.assertTrue(sql.contains("FOR UPDATE"));
                    Object[] args = java.util.Arrays.copyOfRange(inv.getArguments(), 1, inv.getArguments().length);
                    assertEquals(java.util.List.of("0", "owner", "client", "task", "file", 2, "INPUT", "INPUT"), java.util.Arrays.asList(args));
                    return java.util.List.of(java.util.Map.of("content_hash", "a".repeat(64)));
                });
        var row = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "workspaceRow", scope, "task",
                java.util.Map.of("fileId", "file", "version", "2", "purpose", "INPUT"));
        assertEquals(java.util.Map.of("content_hash", "a".repeat(64)), row);
        verifyNoInteractions(storage);
    }

    private static AgentRuntimeAuthentication.Scope runtime(String agent) {
        return new AgentRuntimeAuthentication.Scope("0", "client", "owner", agent, "runtime-1");
    }

    private static String digest() {
        return "sha256:" + "a".repeat(64);
    }
}
