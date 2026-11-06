package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.ControlledImageFollowupAuthorityService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.entity.ChatConversationEntity;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatBountyInteractionV3PreviewServiceTest {
    private final ChatConversationDao conversations=mock(ChatConversationDao.class);
    private final JuyitingConversationScopeService scopes=mock(JuyitingConversationScopeService.class);
    private final AgentTaskExecutionGrantService grants=mock(AgentTaskExecutionGrantService.class);
    private final ChatConversationAssetSourceResolver sources=mock(ChatConversationAssetSourceResolver.class);
    private final ControlledImageFollowupAuthorityService authority=mock(ControlledImageFollowupAuthorityService.class);
    private final ChatBountyBindingStore bindings=mock(ChatBountyBindingStore.class);
    private final ChatBountyInteractionV3PreviewService service=new ChatBountyInteractionV3PreviewService(
            conversations,scopes,grants,sources,authority,bindings);
    private final ServerResolvedSender sender=new ServerResolvedSender("user","Human","owner",
            "client",DisplayNameSource.JIACN);

    @Test void contextReturnsOneConsistentCurrentTupleAndAcceptsZeroTaskAndAssignmentVersions() throws Exception {
        var row=conversation(1,0);
        when(conversations.findScopedById("owner","client","10")).thenReturn(row);
        when(scopes.parsePersistedTargetAgentIds("[\"agent\"]")).thenReturn(List.of("agent"));
        when(grants.observeFollowupContext(new AgentTaskExecutionGrantService.Scope("0","client","owner"),
                "task","agent")).thenReturn(new AgentTaskExecutionGrantService.FollowupContext(
                "task","agent",0,0,1,1));
        when(bindings.find(new ChatBountyBindingStore.Scope("0","owner","client"),"task"))
                .thenReturn(new ChatBountyBindingStore.Binding(0,10L));

        var context=service.context("0",sender,"10");

        assertEquals(1,context.schemaVersion());
        assertEquals("1",context.conversationGeneration());
        assertEquals("0",context.taskVersion());
        assertEquals("0",context.assignmentRevision());
        assertEquals("1",context.baselineGrantVersion());
        assertEquals("1",context.requirementRevision());
        verifyNoInteractions(sources,authority);
        var annotation=ChatBountyInteractionV3PreviewService.class
                .getMethod("context",String.class,ServerResolvedSender.class,String.class)
                .getAnnotation(Transactional.class);
        assertNotNull(annotation);assertTrue(annotation.readOnly());
        assertEquals(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ,annotation.isolation());
        verify(conversations,never()).lockScopedById(anyString(),anyString(),anyString());
        verify(bindings,never()).lock(any(),anyString());
    }

    @Test void contextUsesOneReadOnlyRepeatableReadSpringTransaction() {
        DriverManagerDataSource dataSource=new DriverManagerDataSource(
                "jdbc:h2:mem:v3_context_snapshot;MODE=MYSQL;DB_CLOSE_DELAY=-1","sa","");
        var row=conversation(1,0);
        AtomicInteger reads=new AtomicInteger();
        when(conversations.findScopedById("owner","client","10")).thenAnswer(invocation->{
            assertSnapshotTransaction();reads.incrementAndGet();return row;});
        when(scopes.parsePersistedTargetAgentIds("[\"agent\"]")).thenReturn(List.of("agent"));
        when(grants.observeFollowupContext(any(),eq("task"),eq("agent"))).thenAnswer(invocation->{
            assertSnapshotTransaction();reads.incrementAndGet();
            return new AgentTaskExecutionGrantService.FollowupContext("task","agent",0,0,1,1);});
        when(bindings.find(any(),eq("task"))).thenAnswer(invocation->{
            assertSnapshotTransaction();reads.incrementAndGet();
            return new ChatBountyBindingStore.Binding(0,10L);});
        try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext()) {
            context.register(TransactionConfiguration.class);
            context.registerBean(DataSourceTransactionManager.class,
                    ()->new DataSourceTransactionManager(dataSource));
            context.registerBean(ChatBountyInteractionV3PreviewService.class,()->service);
            context.refresh();
            var proxied=context.getBean(ChatBountyInteractionV3PreviewService.class);
            assertTrue(AopUtils.isCglibProxy(proxied));
            assertEquals("task",proxied.context("0",sender,"10").taskId());
        }
        assertEquals(3,reads.get());
        verify(conversations,times(1)).findScopedById("owner","client","10");
        verify(conversations,never()).lockScopedById(anyString(),anyString(),anyString());
        verify(bindings,never()).lock(any(),anyString());
        verify(grants,never()).currentFollowupContext(any(),anyString(),anyString());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test void bindingSnapshotDoesNotWaitForConcurrentWriterAndRemainsConsistent() throws Exception {
        var dataSource=new DriverManagerDataSource(
                "jdbc:h2:mem:v3_binding_snapshot;MODE=MYSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000","sa","");
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE chat_bounty_binding (tenant_id VARCHAR(50),owner_jiacn VARCHAR(100),"
                +"client_id VARCHAR(50),task_id VARCHAR(100),assignment_revision BIGINT,conversation_id BIGINT)");
        jdbc.update("INSERT INTO chat_bounty_binding VALUES ('0','owner','client','task',0,10)");
        var store=new ChatBountyBindingStore(jdbc);
        var scope=new ChatBountyBindingStore.Scope("0","owner","client");
        var manager=new DataSourceTransactionManager(dataSource);
        var reader=new org.springframework.transaction.support.TransactionTemplate(manager);
        reader.setReadOnly(true);
        reader.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var locked=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var writer=executor.submit(()->new org.springframework.transaction.support.TransactionTemplate(manager)
                    .execute(status->{
                        jdbc.update("UPDATE chat_bounty_binding SET assignment_revision=1");
                        locked.countDown();
                        try { assertTrue(release.await(10,java.util.concurrent.TimeUnit.SECONDS)); }
                        catch(InterruptedException interrupted) { throw new RuntimeException(interrupted); }
                        return null;
                    }));
            assertTrue(locked.await(5,java.util.concurrent.TimeUnit.SECONDS));
            reader.execute(status->{
                assertEquals(0,store.find(scope,"task").assignmentRevision());
                release.countDown();
                try { writer.get(5,java.util.concurrent.TimeUnit.SECONDS); }
                catch(Exception failure) { throw new RuntimeException(failure); }
                assertEquals(0,store.find(scope,"task").assignmentRevision());
                return null;
            });
            assertEquals(1,store.find(scope,"task").assignmentRevision());
        } finally { release.countDown();executor.shutdownNow(); }
    }

    @Test void storageFailureStillReturnsUnavailableWithoutRetryOrWriteFallback() {
        when(conversations.findScopedById("owner","client","10")).thenReturn(conversation(1,0));
        when(scopes.parsePersistedTargetAgentIds("[\"agent\"]")).thenReturn(List.of("agent"));
        when(grants.observeFollowupContext(any(),eq("task"),eq("agent")))
                .thenThrow(new CannotAcquireLockException("storage unavailable"));
        var failure=assertThrows(ChatDeliberationException.class,()->service.context("0",sender,"10"));
        assertEquals(ChatDeliberationException.Reason.PERSISTENCE_ERROR,failure.reason());
        verify(grants,times(1)).observeFollowupContext(any(),eq("task"),eq("agent"));
        verifyNoInteractions(bindings);
    }

    @Test void contextRejectsBindingOutsideTheObservedAssignment() {
        var observed=conversation(1,0);
        when(conversations.findScopedById("owner","client","10")).thenReturn(observed);
        when(scopes.parsePersistedTargetAgentIds("[\"agent\"]")).thenReturn(List.of("agent"));
        when(grants.observeFollowupContext(any(),eq("task"),eq("agent"))).thenReturn(
                new AgentTaskExecutionGrantService.FollowupContext("task","agent",0,0,1,1));
        when(bindings.find(any(),eq("task"))).thenReturn(new ChatBountyBindingStore.Binding(1,10L));

        var failure=assertThrows(ChatDeliberationException.class,()->service.context("0",sender,"10"));
        assertEquals(ChatDeliberationException.Reason.CONFLICT,failure.reason());
        verifyNoInteractions(sources,authority);
    }

    @Test void observedConversationIdDriftConflictsBeforeGrantOrBindingRead() {
        var wrong=conversation(1,0).setId(11L);
        when(conversations.findScopedById("owner","client","10")).thenReturn(wrong);

        var failure=assertThrows(ChatDeliberationException.class,()->service.context("0",sender,"10"));

        assertEquals(ChatDeliberationException.Reason.CONFLICT,failure.reason());
        verifyNoInteractions(grants,bindings,sources,authority);
        verify(conversations,never()).lockScopedById(anyString(),anyString(),anyString());
    }

    @Test void foreignConversationIsOwnerSafeAndDoesNotReadGrantOrBinding() {
        when(conversations.findScopedById("owner","client","10")).thenReturn(null);
        var failure=assertThrows(ChatDeliberationException.class,()->service.context("0",sender,"10"));
        assertEquals(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,failure.reason());
        verifyNoInteractions(grants,bindings,sources,authority);
    }

    private static void assertSnapshotTransaction() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
        assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ,
                TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass=true)
    static class TransactionConfiguration { }

    private static ChatConversationEntity conversation(long generation,long ignoredAssignment) {
        var row=new ChatConversationEntity().setId(10L).setJiacn("owner")
                .setConversationType("juyiting").setConversationScopeType("bounty")
                .setConversationScopeKey("task:task").setTaskId("task")
                .setTargetAgentIds("[\"agent\"]").setLifecycleGeneration(generation);
        row.setTenantId("0");row.setClientId("client");return row;
    }
}
