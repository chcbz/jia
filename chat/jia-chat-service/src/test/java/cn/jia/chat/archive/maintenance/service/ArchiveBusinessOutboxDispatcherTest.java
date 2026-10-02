package cn.jia.chat.archive.maintenance.service;

import cn.jia.chat.archive.maintenance.model.*;
import cn.jia.chat.archive.maintenance.store.ArchiveMaintenanceStore;
import cn.jia.chat.archive.service.ArchiveTransactions;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.service.BuiltinHallAgentSupport;
import cn.jia.chat.service.ChatConversationEventBroker;
import cn.jia.chat.service.ChatConversationService;
import cn.jia.chat.service.JuyitingConversationScopeService;
import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArchiveBusinessOutboxDispatcherTest {
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final String JOB = "job-a";
    private static final String KEY = "EVENT:job-a:1";

    @Test
    void dispatcherIsExplicitlyDefaultDisabled() throws Exception {
        ConditionalOnProperty condition = ArchiveBusinessOutboxDispatcher.class
                .getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition);
        assertEquals("archive.maintenance.business-outbox", condition.prefix());
        assertArrayEquals(new String[]{"enabled"}, condition.name());
        assertEquals("true", condition.havingValue());
        assertFalse(condition.matchIfMissing());
        Path codeLocation = Path.of(ArchiveBusinessOutboxDispatcher.class
                .getProtectionDomain().getCodeSource().getLocation().toURI());
        Path buildDirectory = codeLocation;
        while (buildDirectory != null
                && !"build".equals(String.valueOf(buildDirectory.getFileName()))) {
            buildDirectory = buildDirectory.getParent();
        }
        assertNotNull(buildDirectory, "jia-chat-service build output is unavailable");
        Path packagedDefaults = buildDirectory.resolve("resources/main/application.properties");
        assertTrue(Files.isRegularFile(packagedDefaults),
                "jia-chat-service packaged defaults are unavailable");
        String properties = Files.readString(packagedDefaults, StandardCharsets.UTF_8);
        assertTrue(properties.lines().anyMatch(
                "archive.maintenance.business-outbox.enabled=false"::equals));
    }

    @Test
    void persistsOneSanitizedMessageAndAcknowledgesTheSameClaim() {
        Fixture f = fixture();
        when(f.store.findBusinessOutboxCandidates(any(), isNull(), isNull(), eq(32)))
                .thenReturn(List.of(f.ready), List.of());
        when(f.store.claimBusinessOutbox(KEY, 0, "READY", NOW, NOW.plusSeconds(30))).thenReturn(1);
        when(f.store.findBusinessOutbox(KEY, true)).thenReturn(f.leased);
        when(f.store.findJob(JOB, false)).thenReturn(f.job);
        when(f.store.findJob(JOB, true)).thenReturn(f.job);
        when(f.store.findManagerGrant(f.actor, "platform-classics", true)).thenReturn(f.manager);
        when(f.store.findConfirmedRequestForJob(JOB, true)).thenReturn(f.confirmation);
        when(f.conversationDao.lockScopedByIdIncludingDeleted("owner-a", "client-a", "41"))
                .thenReturn(f.conversation);
        when(f.scopes.parsePersistedTargetAgentIds("[\"songjiang\"]"))
                .thenReturn(List.of("songjiang"));
        when(f.store.findJobEvent(JOB, 1, true)).thenReturn(new ArchiveJobEventRecord(
                JOB, 1, 1, "JOB_CREATED", 1, "{\"private\":\"must-not-project\"}", NOW.toString()));
        ChatMessageEntity saved = new ChatMessageEntity();
        saved.setId(77L);
        when(f.conversations.appendOwnedMessage(eq("owner-a"), eq("client-a"), any(), eq(4L)))
                .thenReturn(saved);
        when(f.store.completeBusinessOutbox(KEY, 1, "DELIVERED", 77L, null)).thenReturn(1);
        when(f.conversations.isLiveGeneration("owner-a", "client-a", "41", 4)).thenReturn(true);

        assertEquals(1, f.dispatcher.dispatchOnce());
        assertEquals(0, f.dispatcher.dispatchOnce());
        ArgumentCaptor<ChatMessageEntity> message = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(f.conversations, times(1)).appendOwnedMessage(eq("owner-a"), eq("client-a"),
                message.capture(), eq(4L));
        assertTrue(message.getValue().getContent().contains("archive_maintenance_receipt"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> payload = JsonUtil.fromJson(
                message.getValue().getContent(), java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> compatibility =
                (java.util.Map<String, Object>) payload.get("archiveMaintenance");
        assertEquals(JOB, compatibility.get("jobId"));
        assertTrue(message.getValue().getContent().contains("JOB_CREATED"));
        assertFalse(message.getValue().getContent().contains("must-not-project"));
        verify(f.store, times(1)).completeBusinessOutbox(KEY, 1, "DELIVERED", 77L, null);
    }

    @Test
    void staleGenerationTerminatesAsNoTargetWithoutSongjiangFallback() {
        Fixture f = fixture();
        when(f.store.findBusinessOutboxCandidates(any(), isNull(), isNull(), eq(32)))
                .thenReturn(List.of(f.ready));
        when(f.store.claimBusinessOutbox(anyString(), anyLong(), anyString(), any(), any())).thenReturn(1);
        when(f.store.findBusinessOutbox(KEY, true)).thenReturn(f.leased);
        when(f.store.findJob(JOB, false)).thenReturn(f.job);
        when(f.store.findJob(JOB, true)).thenReturn(f.job);
        when(f.store.findManagerGrant(f.actor, "platform-classics", true)).thenReturn(f.manager);
        when(f.store.findJobEvent(JOB, 1, true)).thenReturn(new ArchiveJobEventRecord(
                JOB, 1, 1, "JOB_CREATED", 1, "{}", NOW.toString()));
        when(f.store.findConfirmedRequestForJob(JOB, true)).thenReturn(f.confirmation);
        ChatConversationEntity rotated = f.conversation.setLifecycleGeneration(5L);
        when(f.conversationDao.lockScopedByIdIncludingDeleted("owner-a", "client-a", "41"))
                .thenReturn(rotated);
        when(f.store.completeBusinessOutbox(KEY, 1, "NO_TARGET", null,
                "CONVERSATION_GENERATION_FENCED")).thenReturn(1);

        assertEquals(1, f.dispatcher.dispatchOnce());
        verify(f.conversations, never()).appendOwnedMessage(anyString(), anyString(), any(), anyLong());
        verify(f.store).completeBusinessOutbox(KEY, 1, "NO_TARGET", null,
                "CONVERSATION_GENERATION_FENCED");
    }

    @Test
    void directPrivateTargetMismatchTerminatesWithoutSongjiangFallback() {
        Fixture f = fixture();
        ArchiveConfirmedRequestRecord direct = new ArchiveConfirmedRequestRecord("confirm-a",
                "intent-a", "0", "client-a", "owner-a", "platform-classics", "{}",
                "b".repeat(64), "MANUAL", "41", "100", 4L, "c".repeat(64),
                "DIRECT_PRIVATE", "agent-a", 2);
        ChatConversationEntity wrongPrivate = f.conversation
                .setConversationScopeType("private")
                .setTargetAgentId("agent-b")
                .setTargetAgentIds("[\"agent-b\"]");
        when(f.store.findBusinessOutboxCandidates(any(), isNull(), isNull(), eq(32)))
                .thenReturn(List.of(f.ready));
        when(f.store.claimBusinessOutbox(anyString(), anyLong(), anyString(), any(), any())).thenReturn(1);
        when(f.store.findBusinessOutbox(KEY, true)).thenReturn(f.leased);
        when(f.store.findJob(JOB, false)).thenReturn(f.job);
        when(f.store.findJob(JOB, true)).thenReturn(f.job);
        when(f.store.findManagerGrant(f.actor, "platform-classics", true)).thenReturn(f.manager);
        when(f.store.findJobEvent(JOB, 1, true)).thenReturn(new ArchiveJobEventRecord(
                JOB, 1, 1, "JOB_CREATED", 1, "{}", NOW.toString()));
        when(f.store.findConfirmedRequestForJob(JOB, true)).thenReturn(direct);
        when(f.conversationDao.lockScopedByIdIncludingDeleted("owner-a", "client-a", "41"))
                .thenReturn(wrongPrivate);
        when(f.scopes.parsePersistedTargetAgentIds("[\"agent-b\"]"))
                .thenReturn(List.of("agent-b"));
        when(f.store.completeBusinessOutbox(KEY, 1, "NO_TARGET", null,
                "CONVERSATION_GENERATION_FENCED")).thenReturn(1);

        assertEquals(1, f.dispatcher.dispatchOnce());
        verify(f.conversations, never()).appendOwnedMessage(anyString(), anyString(), any(), anyLong());
        verify(f.store).completeBusinessOutbox(KEY, 1, "NO_TARGET", null,
                "CONVERSATION_GENERATION_FENCED");
    }

    @Test
    void poisonHeadIsRetriedAndDoesNotBlockTheNextCandidate() {
        Fixture f = fixture();
        ArchiveBusinessOutboxRecord poison = new ArchiveBusinessOutboxRecord("EVENT:missing:1",
                "JOB_EVENT", "missing", 1L, null, "READY", 0, 0, NOW, null, null, null);
        ArchiveBusinessOutboxRecord poisonLease = new ArchiveBusinessOutboxRecord("EVENT:missing:1",
                "JOB_EVENT", "missing", 1L, null, "LEASED", 1, 1, NOW,
                NOW.plusSeconds(30), null, null);
        when(f.store.findBusinessOutboxCandidates(any(), isNull(), isNull(), eq(32)))
                .thenReturn(List.of(poison, f.ready));
        when(f.store.claimBusinessOutbox(anyString(), eq(0L), eq("READY"), eq(NOW),
                eq(NOW.plusSeconds(30)))).thenReturn(1);
        when(f.store.findBusinessOutbox("EVENT:missing:1", true)).thenReturn(poisonLease);
        when(f.store.findBusinessOutbox(KEY, true)).thenReturn(f.leased);
        when(f.store.findJob("missing", false)).thenThrow(new IllegalStateException("poison"));
        when(f.store.findJob(JOB, false)).thenReturn(f.job);
        when(f.store.findJob(JOB, true)).thenReturn(f.job);
        when(f.store.findManagerGrant(f.actor, "platform-classics", true)).thenReturn(null);
        when(f.store.completeBusinessOutbox(KEY, 1, "NO_TARGET", null,
                "MANAGER_AUTHORIZATION_REVOKED")).thenReturn(1);
        when(f.store.retryBusinessOutbox(eq("EVENT:missing:1"), eq(1L), any(),
                eq("PROJECTION_UNAVAILABLE"))).thenReturn(1);

        assertEquals(1, f.dispatcher.dispatchOnce());
        verify(f.store).retryBusinessOutbox(eq("EVENT:missing:1"), eq(1L), any(),
                eq("PROJECTION_UNAVAILABLE"));
        verify(f.store).completeBusinessOutbox(KEY, 1, "NO_TARGET", null,
                "MANAGER_AUTHORIZATION_REVOKED");
    }

    private Fixture fixture() {
        ArchiveMaintenanceStore store = mock(ArchiveMaintenanceStore.class);
        ChatConversationDao conversationDao = mock(ChatConversationDao.class);
        ChatConversationService conversations = mock(ChatConversationService.class);
        ChatConversationEventBroker broker = mock(ChatConversationEventBroker.class);
        JuyitingConversationScopeService scopes = mock(JuyitingConversationScopeService.class);
        BuiltinHallAgentSupport builtin = mock(BuiltinHallAgentSupport.class);
        when(builtin.defaultAgentId()).thenReturn("songjiang");
        ArchiveTransactions tx = new ArchiveTransactions() {
            @Override public <T> T required(Supplier<T> action) { return action.get(); }
            @Override public <T> T requiresNew(Supplier<T> action) { return action.get(); }
            @Override public void afterCommit(Runnable action) { action.run(); }
        };
        ArchiveBusinessOutboxDispatcher dispatcher = new ArchiveBusinessOutboxDispatcher(store, tx,
                conversationDao, conversations, broker, scopes, builtin,
                Clock.fixed(NOW, ZoneOffset.UTC));
        ArchiveActorScope actor = new ArchiveActorScope("0", "client-a", "owner-a");
        ArchiveMaintenanceJobRecord job = new ArchiveMaintenanceJobRecord(JOB, null,
                "platform-classics", "0", "client-a", "owner-a", null, null, null,
                null, null, 1, "MANUAL", "ADD_WORK", "work-a", "work-a", "Work A",
                null, null, null, null, "WAITING_INPUT", "SOURCE", 1, null, null,
                "intent-a", "a".repeat(64), null);
        ArchiveManagerGrantRecord manager = new ArchiveManagerGrantRecord("platform-classics",
                "0", "client-a", "owner-a", "job.manage", 1, "ACTIVE");
        ArchiveConfirmedRequestRecord confirmation = new ArchiveConfirmedRequestRecord("confirm-a",
                "intent-a", "0", "client-a", "owner-a", "platform-classics", "{}",
                "b".repeat(64), "MANUAL", "41", "100", 4L, "c".repeat(64),
                "SONGJIANG", null, 2);
        ChatConversationEntity conversation = new ChatConversationEntity().setId(41L)
                .setJiacn("owner-a").setConversationType("juyiting")
                .setConversationScopeType("public").setTargetAgentIds("[\"songjiang\"]")
                .setLifecycleGeneration(4L);
        conversation.setTenantId("0");
        conversation.setClientId("client-a");
        ArchiveBusinessOutboxRecord ready = new ArchiveBusinessOutboxRecord(KEY, "JOB_EVENT",
                JOB, 1L, null, "READY", 0, 0, NOW, null, null, null);
        ArchiveBusinessOutboxRecord leased = new ArchiveBusinessOutboxRecord(KEY, "JOB_EVENT",
                JOB, 1L, null, "LEASED", 1, 1, NOW, NOW.plusSeconds(30), null, null);
        return new Fixture(store, conversationDao, conversations, broker, scopes, dispatcher,
                actor, job, manager, confirmation, conversation, ready, leased);
    }

    private record Fixture(ArchiveMaintenanceStore store, ChatConversationDao conversationDao,
            ChatConversationService conversations, ChatConversationEventBroker broker,
            JuyitingConversationScopeService scopes, ArchiveBusinessOutboxDispatcher dispatcher,
            ArchiveActorScope actor, ArchiveMaintenanceJobRecord job,
            ArchiveManagerGrantRecord manager, ArchiveConfirmedRequestRecord confirmation,
            ChatConversationEntity conversation, ArchiveBusinessOutboxRecord ready,
            ArchiveBusinessOutboxRecord leased) { }
}
