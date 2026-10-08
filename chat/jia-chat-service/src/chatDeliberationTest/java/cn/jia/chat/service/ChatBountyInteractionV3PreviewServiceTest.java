package cn.jia.chat.service;

import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.impl.AgentTaskMutationTransactionImpl;
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
        when(conversations.lockScopedById("owner","client","10")).thenReturn(row);
        when(scopes.parsePersistedTargetAgentIds("[\"agent\"]")).thenReturn(List.of("agent"));
        when(grants.currentFollowupContext(new AgentTaskExecutionGrantService.Scope("0","client","owner"),
                "task","agent")).thenReturn(new AgentTaskExecutionGrantService.FollowupContext(
                "task","agent",0,0,1,1));
        when(bindings.lock(new ChatBountyBindingStore.Scope("0","owner","client"),"task"))
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
        assertNotNull(annotation);assertFalse(annotation.readOnly());
    }

    @Test void contextUsesWritableSpringTransactionForRequiredTaskAndProjectionLocks() {
        DriverManagerDataSource dataSource=new DriverManagerDataSource(
                "jdbc:h2:mem:v3_context_lock;MODE=MYSQL;DB_CLOSE_DELAY=-1","sa","");
        ChatConversationDao realConversations=mock(ChatConversationDao.class);
        JuyitingConversationScopeService realScopes=mock(JuyitingConversationScopeService.class);
        AgentTaskExecutionGrantService realGrants=mock(AgentTaskExecutionGrantService.class);
        ChatBountyBindingStore realBindings=mock(ChatBountyBindingStore.class);
        AgentTaskMetaDao roots=mock(AgentTaskMetaDao.class);
        AtomicInteger lockReads=new AtomicInteger();
        AgentTaskMutationTransaction mutations=new AgentTaskMutationTransactionImpl(roots,
                new DataSourceTransactionManager(dataSource));
        ChatConversationEntity row=conversation(1,0);
        when(realConversations.findScopedById("owner","client","10")).thenReturn(row);
        when(realScopes.parsePersistedTargetAgentIds("[\"agent\"]")).thenReturn(List.of("agent"));
        when(roots.findByTaskIdForUpdateInOwnerScope("0","client","owner","task"))
                .thenAnswer(invocation->{
                    assertWritableTransaction();
                    lockReads.incrementAndGet();
                    return taskRoot();
                });
        when(realGrants.currentFollowupContext(
                new AgentTaskExecutionGrantService.Scope("0","client","owner"),"task","agent"))
                .thenAnswer(invocation->mutations.executeWithLockedTaskRootInOwnerScope(
                        "0","client","owner","task",locked->new AgentTaskExecutionGrantService.FollowupContext(
                                "task","agent",0,0,1,1)));
        when(realBindings.lock(new ChatBountyBindingStore.Scope("0","owner","client"),"task"))
                .thenAnswer(invocation->{assertWritableTransaction();lockReads.incrementAndGet();
                    return new ChatBountyBindingStore.Binding(0,10L);});
        when(realConversations.lockScopedById("owner","client","10"))
                .thenAnswer(invocation->{assertWritableTransaction();lockReads.incrementAndGet();return row;});

        try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext()) {
            context.register(TransactionConfiguration.class);
            context.registerBean(DataSourceTransactionManager.class,
                    ()->new DataSourceTransactionManager(dataSource));
            context.registerBean(ChatBountyInteractionV3PreviewService.class,
                    ()->new ChatBountyInteractionV3PreviewService(realConversations,realScopes,realGrants,
                            mock(ChatConversationAssetSourceResolver.class),
                            mock(ControlledImageFollowupAuthorityService.class),realBindings));
            context.refresh();
            ChatBountyInteractionV3PreviewService proxied=
                    context.getBean(ChatBountyInteractionV3PreviewService.class);
            assertTrue(AopUtils.isCglibProxy(proxied));
            assertEquals("task",proxied.context("0",sender,"10").taskId());
        }
        assertEquals(3,lockReads.get());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test void readOnlyOuterTransactionTurnsTheRequiredLockFailureIntoUnavailable() {
        DriverManagerDataSource dataSource=new DriverManagerDataSource(
                "jdbc:h2:mem:v3_context_read_only_guard;MODE=MYSQL;DB_CLOSE_DELAY=-1","sa","");
        ChatConversationDao realConversations=mock(ChatConversationDao.class);
        JuyitingConversationScopeService realScopes=mock(JuyitingConversationScopeService.class);
        AgentTaskExecutionGrantService realGrants=mock(AgentTaskExecutionGrantService.class);
        ChatBountyBindingStore realBindings=mock(ChatBountyBindingStore.class);
        AgentTaskMetaDao roots=mock(AgentTaskMetaDao.class);
        DataSourceTransactionManager manager=new DataSourceTransactionManager(dataSource);
        AgentTaskMutationTransaction mutations=new AgentTaskMutationTransactionImpl(roots,manager);
        ChatConversationEntity row=conversation(1,0);
        when(realConversations.findScopedById("owner","client","10")).thenReturn(row);
        when(realScopes.parsePersistedTargetAgentIds("[\"agent\"]")).thenReturn(List.of("agent"));
        when(roots.findByTaskIdForUpdateInOwnerScope("0","client","owner","task"))
                .thenAnswer(invocation->{
                    if(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                        throw new CannotAcquireLockException(
                                "SELECT FOR UPDATE is not valid in a read-only transaction");
                    return taskRoot();
                });
        when(realGrants.currentFollowupContext(
                new AgentTaskExecutionGrantService.Scope("0","client","owner"),"task","agent"))
                .thenAnswer(invocation->mutations.executeWithLockedTaskRootInOwnerScope(
                        "0","client","owner","task",locked->new AgentTaskExecutionGrantService.FollowupContext(
                                "task","agent",0,0,1,1)));
        ChatBountyInteractionV3PreviewService direct=new ChatBountyInteractionV3PreviewService(
                realConversations,realScopes,realGrants,mock(ChatConversationAssetSourceResolver.class),
                mock(ControlledImageFollowupAuthorityService.class),realBindings);
        org.springframework.transaction.support.TransactionTemplate readOnly=
                new org.springframework.transaction.support.TransactionTemplate(manager);
        readOnly.setReadOnly(true);

        ChatDeliberationException failure=assertThrows(ChatDeliberationException.class,
                ()->readOnly.execute(status->direct.context("0",sender,"10")));
        assertEquals(ChatDeliberationException.Reason.PERSISTENCE_ERROR,failure.reason());
        verifyNoInteractions(realBindings);
        verify(realConversations,never()).lockScopedById(anyString(),anyString(),anyString());
    }

    @Test void contextNeverCombinesObservedConversationWithChangedBindingOrGeneration() {
        var observed=conversation(1,0);var changed=conversation(2,0);
        when(conversations.findScopedById("owner","client","10")).thenReturn(observed);
        when(conversations.lockScopedById("owner","client","10")).thenReturn(changed);
        when(scopes.parsePersistedTargetAgentIds("[\"agent\"]")).thenReturn(List.of("agent"));
        when(grants.currentFollowupContext(any(),eq("task"),eq("agent"))).thenReturn(
                new AgentTaskExecutionGrantService.FollowupContext("task","agent",0,0,1,1));
        when(bindings.lock(any(),eq("task"))).thenReturn(new ChatBountyBindingStore.Binding(0,10L));

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

    private static void assertWritableTransaction() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass=true)
    static class TransactionConfiguration { }

    private static AgentTaskMetaEntity taskRoot() {
        AgentTaskMetaEntity root=new AgentTaskMetaEntity().setTaskId("task")
                .setTaskVersion(0L).setCurrentEventVersion(0L);
        root.setTenantId("0");root.setClientId("client");root.setOwnerJiacn("owner");
        return root;
    }

    private static ChatConversationEntity conversation(long generation,long ignoredAssignment) {
        var row=new ChatConversationEntity().setId(10L).setJiacn("owner")
                .setConversationType("juyiting").setConversationScopeType("bounty")
                .setConversationScopeKey("task:task").setTaskId("task")
                .setTargetAgentIds("[\"agent\"]").setLifecycleGeneration(generation);
        row.setTenantId("0");row.setClientId("client");return row;
    }
}
