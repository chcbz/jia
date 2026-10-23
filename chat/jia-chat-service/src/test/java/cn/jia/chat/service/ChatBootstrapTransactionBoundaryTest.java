package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.SkillCapabilityLookup;
import cn.jia.chat.api.ChatTypedDeliberationWire;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import cn.jia.chat.entity.ChatConversationEntity;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Spring propagation: pending conversation visibility must survive nested bootstrap admission. */
class ChatBootstrapTransactionBoundaryTest {
    @Test void preparedBootstrapKeepsOuterTransactionAndNeverPreparesPackagesInsideIt() {
        var manager = new TestTransactions();
        var conversations = mock(ChatConversationDao.class);
        var mutations = mock(AgentTaskMutationTransaction.class);
        var typed = mock(ChatTypedDeliberationService.class);
        var store = mock(ChatTypedDeliberationStore.class);
        when(typed.store()).thenReturn(store);
        var packages = mock(ChatSkillPackagePreparationService.class);
        var service = new ChatTypedDiscussionAdmissionService(mutations, mock(ChatBountyBindingStore.class),
                conversations, mock(JuyitingConversationScopeService.class), mock(ChatDeliberationService.class),
                mock(ChatDeliberationDao.class), mock(ChatTypedDeliberationContextService.class), typed,
                mock(ChatActionFinalService.class));
        service.setPackagePreparation(packages);
        var proxyFactory = new ProxyFactory(service);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        var proxy = (ChatTypedDiscussionAdmissionService) proxyFactory.getProxy();
        var conversation = new ChatConversationEntity().setTaskId("task-1").setJiacn("owner")
                .setLifecycleGeneration(1L);
        conversation.setTenantId("0"); conversation.setClientId("client");
        when(conversations.findScopedById("owner", "client", "123"))
                .thenAnswer(call -> manager.current.get() != null ? conversation : null);
        var reachedRoot = new IllegalStateException("reached exact scoped task root");
        doThrow(reachedRoot).when(mutations).executeWithLockedTaskRootInOwnerScope(
                eq("0"), eq("client"), eq("owner"), eq("task-1"), any());
        var sender = new ServerResolvedSender(ServerResolvedSender.USER_TYPE, "owner", "owner", "client", DisplayNameSource.JIACN);
        var command = new ChatTypedDeliberationWire.DiscussionCommand("DISCUSSION", "task-1", 1L,
                "Inspect the attached image", null, null, null, null, List.of(), "TASK_DEFAULT");
        proxy.prepareBootstrap(new SkillCapabilityLookup.Scope("0", "client", "owner", "agent-1"));
        verify(packages).prepare(new SkillCapabilityLookup.Scope("0", "client", "owner", "agent-1"));
        clearInvocations(packages);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            // Reproduces the old call: NOT_SUPPORTED hides the just-created conversation.
            assertThrows(ChatDeliberationException.class,
                    () -> proxy.admit("0", sender, "123", "original-key", command));
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            // The bootstrap-only method joins it and reaches the same authoritative root checks.
            assertSame(reachedRoot, assertThrows(IllegalStateException.class,
                    () -> proxy.admitPreparedBootstrap("0", sender, "123", "original-key", command)));
            assertThrows(ChatDeliberationException.class,
                    () -> proxy.prepareBootstrap(new SkillCapabilityLookup.Scope("0", "client", "owner", "agent-1")));
            status.setRollbackOnly();
        });
        assertEquals(1, manager.rollbacks);
        assertEquals(0, manager.commits);
        verifyNoInteractions(packages);
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                () -> proxy.admitPreparedBootstrap("0", sender, "123", "original-key", command));
    }

    static final class TestTransactions extends AbstractPlatformTransactionManager {
        final ThreadLocal<Object> current = new ThreadLocal<>();
        int commits, rollbacks;
        @Override protected Object doGetTransaction() { return current.get() == null ? new Object() : current.get(); }
        @Override protected boolean isExistingTransaction(Object tx) { return tx == current.get(); }
        @Override protected void doBegin(Object tx, TransactionDefinition definition) { current.set(tx); }
        @Override protected Object doSuspend(Object tx) { current.remove(); return tx; }
        @Override protected void doResume(Object tx, Object suspended) { current.set(suspended); }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
        @Override protected void doSetRollbackOnly(DefaultTransactionStatus status) { }
        @Override protected void doCleanupAfterCompletion(Object tx) { current.remove(); }
    }
}
