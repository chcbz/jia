package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatBountyRequestIndexStore;
import cn.jia.chat.entity.ChatConversationEntity;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatBountyRequestIndexServiceTest {
    public interface TaskRoots {
        AgentTaskMetaEntity findByTaskIdInOwnerScope(String tenant, String client, String owner, String taskId);
    }

    @Test void historicalTerminalTaskAndOldAssignmentRemainReadableWithoutAnyGrantLookup() {
        Fixture f = new Fixture();
        when(f.store.highWatermark(any())).thenReturn(3L);
        when(f.store.page(any(), eq(0L), eq(3L), eq(3))).thenReturn(List.of(
                f.row(1, "request-1"), f.row(2, "request-2"), f.row(3, "request-3")));
        when(f.deliberations.getRequest(eq("0"), eq("owner"), eq("client"), anyString()))
                .thenAnswer(invocation -> f.request(invocation.getArgument(3), "task-1", "old-agent", "0"));
        var page = f.service.read("0", "owner", "client", "42",
                new ChatBountyRequestIndexService.Query(null, null, null, "2"));
        assertTrue(page.hasMore());
        assertEquals("2", page.nextAfter());
        assertEquals(List.of("request-1", "request-2"), page.entries().stream()
                .map(entry -> entry.request().requestId()).toList());
        assertEquals("old-agent", page.entries().getFirst().request().steps().getFirst().targetAgentId());
        assertEquals("0", page.entries().getFirst().request().steps().getFirst().assignmentRevision());
        verify(f.roots).findByTaskIdInOwnerScope("0", "client", "owner", "task-1");
        verify(f.store).page(any(), eq(0L), eq(3L), eq(3));
    }

    @Test void continuationFixesThroughAndGenerationWhileLaterFromZeroRescanCanSupplementLowOrdinals() {
        Fixture f = new Fixture();
        when(f.store.page(any(), eq(2L), eq(4L), eq(101))).thenReturn(List.of(f.row(4, "request-4")));
        when(f.deliberations.getRequest(anyString(), anyString(), anyString(), eq("request-4")))
                .thenReturn(f.request("request-4", "task-1", "agent", "1"));
        var continued = f.service.read("0", "owner", "client", "42",
                new ChatBountyRequestIndexService.Query("7", "2", "4", null));
        assertEquals("4", continued.through());
        verify(f.store, never()).highWatermark(any());

        reset(f.store, f.deliberations);
        when(f.store.highWatermark(any())).thenReturn(4L);
        when(f.store.page(any(), eq(0L), eq(4L), eq(101))).thenReturn(List.of(
                f.row(1, "request-1"), f.row(2, "late-request"), f.row(4, "request-4")));
        when(f.deliberations.getRequest(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(i -> f.request(i.getArgument(3), "task-1", "agent", "1"));
        var rescanned = f.service.read("0", "owner", "client", "42",
                new ChatBountyRequestIndexService.Query(null, "0", null, null));
        assertEquals(List.of("1", "2", "4"), rescanned.entries().stream()
                .map(ChatBountyRequestIndexService.Entry::ordinal).toList());
    }

    @Test void strictCursorDomainsAndContinuationRequirementsFailBeforeAnyDatabaseRead() {
        Fixture f = new Fixture();
        List<ChatBountyRequestIndexService.Query> invalid = List.of(
                new ChatBountyRequestIndexService.Query("01", null, null, null),
                new ChatBountyRequestIndexService.Query(null, "1", null, null),
                new ChatBountyRequestIndexService.Query(null, "0", "1", null),
                new ChatBountyRequestIndexService.Query("7", "2", "1", null),
                new ChatBountyRequestIndexService.Query("7", "-1", "1", null),
                new ChatBountyRequestIndexService.Query("9223372036854775808", null, null, null),
                new ChatBountyRequestIndexService.Query(null, null, null, "0"));
        for (var query : invalid) assertEquals(ChatBountyRequestIndexService.Reason.INVALID_REQUEST,
                assertThrows(ChatBountyRequestIndexService.Failure.class,
                        () -> f.service.read("0", "owner", "client", "42", query)).reason());
        verifyNoInteractions(f.conversations, f.roots, f.store, f.deliberations);
    }

    @Test void generationRootAndProjectionMismatchesFailClosedWithoutPartialPage() {
        Fixture f = new Fixture();
        assertEquals(ChatBountyRequestIndexService.Reason.CONFLICT,
                assertThrows(ChatBountyRequestIndexService.Failure.class, () -> f.service.read(
                        "0", "owner", "client", "42",
                        new ChatBountyRequestIndexService.Query("8", null, null, null))).reason());
        verifyNoInteractions(f.roots, f.store, f.deliberations);

        when(f.store.highWatermark(any())).thenReturn(1L);
        when(f.store.page(any(), anyLong(), anyLong(), anyInt())).thenReturn(List.of(f.row(1, "request-1")));
        when(f.deliberations.getRequest(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(f.request("request-1", "other-task", "agent", "1"));
        assertEquals(ChatBountyRequestIndexService.Reason.UNAVAILABLE,
                assertThrows(ChatBountyRequestIndexService.Failure.class, () -> f.service.read(
                        "0", "owner", "client", "42",
                        new ChatBountyRequestIndexService.Query(null, null, null, null))).reason());
    }

    @Test void unsupportedTenantNeverFallsBackToZeroAndReadBoundaryIsExplicitRepeatableReadOnly() throws Exception {
        Fixture f = new Fixture();
        assertEquals(ChatBountyRequestIndexService.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(ChatBountyRequestIndexService.Failure.class, () -> f.service.read(
                        "tenant-x", "owner", "client", "42", null)).reason());
        verifyNoInteractions(f.conversations, f.roots, f.store, f.deliberations);
        Transactional tx = ChatBountyRequestIndexService.class.getMethod("read", String.class,
                String.class, String.class, String.class, ChatBountyRequestIndexService.Query.class)
                .getAnnotation(Transactional.class);
        assertNotNull(tx);
        assertTrue(tx.readOnly());
        assertEquals(Isolation.REPEATABLE_READ, tx.isolation());
    }

    final class Fixture {
        final ChatBountyRequestIndexStore store = mock(ChatBountyRequestIndexStore.class);
        final ChatConversationDao conversations = mock(ChatConversationDao.class);
        final TaskRoots roots = mock(TaskRoots.class);
        final ChatDeliberationService deliberations = mock(ChatDeliberationService.class);
        final ChatBountyRequestIndexService service;
        Fixture() {
            when(conversations.findScopedById("owner", "client", "42")).thenReturn(conversation());
            when(roots.findByTaskIdInOwnerScope("0", "client", "owner", "task-1")).thenReturn(task());
            service = new ChatBountyRequestIndexService(store, conversations, roots, deliberations);
        }
        ChatConversationEntity conversation() {
            ChatConversationEntity value = new ChatConversationEntity().setId(42L).setJiacn("owner")
                    .setConversationType("juyiting").setConversationScopeType("bounty")
                    .setConversationScopeKey("task:task-1").setTaskId("task-1")
                    .setLifecycleGeneration(7L);
            value.setTenantId("0"); value.setClientId("client"); return value;
        }
        AgentTaskMetaEntity task() {
            AgentTaskMetaEntity value = new AgentTaskMetaEntity().setTaskId("task-1")
                    .setRewardStatus("completed").setTaskVersion(9L);
            value.setTenantId("0"); value.setClientId("client"); value.setOwnerJiacn("owner");
            return value;
        }
        ChatBountyRequestIndexStore.Row row(long ordinal, String requestId) {
            return new ChatBountyRequestIndexStore.Row(ordinal, "0", "owner", "client",
                    requestId, 1, "42", 7);
        }
        ChatDeliberationService.RequestView request(String requestId, String taskId,
                String target, String assignmentRevision) {
            var step = new ChatDeliberationService.StepView("step-" + requestId, "1", taskId,
                    assignmentRevision, target, "EXECUTE", "COMPLETED", "2",
                    "intent-" + requestId, "execution-" + requestId, "OUTPUT_COMMITTED");
            return new ChatDeliberationService.RequestView(requestId, "1", "42", "7", "101",
                    "COMPLETED", "2", List.of(), List.of(step));
        }
    }
}
