package cn.jia.point.service.impl;

import cn.jia.core.exception.EsRuntimeException;
import cn.jia.point.dao.PointAccountDao;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PointAccountServiceImplTest {
    @Test void defaultPartitionIsProductionAndReadsDoNotCreateAccounts() {
        var dao = mock(PointAccountDao.class);
        var service = new PointAccountServiceImpl(dao, "0", false, "");
        assertEquals("0", service.tenantId());
        assertEquals(0, service.balance("alice"));
        verify(dao).balance("0", "alice");
        verifyNoMoreInteractions(dao);
    }

    @Test void greyNeverFallsBackToProductionBalance() {
        var dao = mock(PointAccountDao.class);
        when(dao.balance("0", "alice")).thenReturn(999);
        var service = new PointAccountServiceImpl(dao, "0", true, "1");
        assertEquals("1", service.tenantId());
        assertEquals(0, service.balance("alice"));
        verify(dao, never()).balance("0", "alice");
    }

    @Test void changingBalanceUsesTheSameFixedScopeAndLockedResult() {
        var dao = mock(PointAccountDao.class);
        when(dao.lockBalance("1", "alice")).thenReturn(20);
        when(dao.change(eq("1"), eq("alice"), eq(-5), anyLong())).thenReturn(1);
        var service = new PointAccountServiceImpl(dao, "0", true, "1");
        assertEquals(15, service.changeAndGet("alice", -5));
        var order = inOrder(dao);
        order.verify(dao).ensureAccount(eq("1"), eq("alice"), anyLong());
        order.verify(dao).lockBalance("1", "alice");
        order.verify(dao).change(eq("1"), eq("alice"), eq(-5), anyLong());
        order.verifyNoMoreInteractions();
    }

    @Test void insufficientPointsPreserveExistingBusinessErrorAndNeverUpdate() {
        var dao = mock(PointAccountDao.class);
        when(dao.lockBalance("0", "alice")).thenReturn(4);
        var service = new PointAccountServiceImpl(dao, "0", false, "");
        assertThrows(EsRuntimeException.class, () -> service.changeAndGet("alice", -5));
        verify(dao, never()).change(anyString(), anyString(), anyInt(), anyLong());
    }

    @Test void signedIntOverflowCannotWrapIntoAValidBalance() {
        var dao = mock(PointAccountDao.class);
        when(dao.lockBalance("0", "alice")).thenReturn(Integer.MAX_VALUE);
        var service = new PointAccountServiceImpl(dao, "0", false, "");
        assertThrows(IllegalArgumentException.class, () -> service.changeAndGet("alice", 1));
        verify(dao, never()).change(anyString(), anyString(), anyInt(), anyLong());
    }

    @Test void minimumIntDebitCannotBecomeACredit() {
        var dao = mock(PointAccountDao.class);
        when(dao.lockBalance("0", "alice")).thenReturn(1);
        var service = new PointAccountServiceImpl(dao, "0", false, "");
        assertThrows(EsRuntimeException.class, () -> service.changeAndGet("alice", Integer.MIN_VALUE));
        verify(dao, never()).change(anyString(), anyString(), anyInt(), anyLong());
    }

    @Test void corruptOrMissingLockedRowAndFailedUpdateDoNotReportSuccess() {
        var dao = mock(PointAccountDao.class);
        var service = new PointAccountServiceImpl(dao, "0", false, "");
        assertThrows(IllegalStateException.class, () -> service.changeAndGet("alice", 1));
        when(dao.lockBalance("0", "alice")).thenReturn(-1);
        assertThrows(IllegalStateException.class, () -> service.changeAndGet("alice", 1));
        when(dao.lockBalance("0", "alice")).thenReturn(2);
        assertThrows(IllegalStateException.class, () -> service.changeAndGet("alice", 1));
    }

    @Test void invalidIdentityFailsBeforeAnyDatabaseAccess() {
        var dao = mock(PointAccountDao.class);
        var service = new PointAccountServiceImpl(dao, "0", false, "");
        for (String invalid : new String[]{"", " alice", "alice ", "ali\nce", "x".repeat(51), "\uD800"}) {
            assertThrows(IllegalArgumentException.class, () -> service.balance(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> service.changeAndGet(null, 1));
        verifyNoInteractions(dao);
    }

    @Test void invalidFixedProcessConfigurationFailsAtConstruction() {
        var dao = mock(PointAccountDao.class);
        assertThrows(IllegalArgumentException.class, () -> new PointAccountServiceImpl(dao, "0", true, ""));
        assertThrows(IllegalArgumentException.class, () -> new PointAccountServiceImpl(dao, "0", true, "0"));
    }

    @Test void mutationRequiresTheBusinessTransactionForBalanceAndLedgerAtomicity() throws Exception {
        var method = PointAccountServiceImpl.class.getMethod("changeAndGet", String.class, int.class);
        var attribute = new AnnotationTransactionAttributeSource()
                .getTransactionAttribute(method, PointAccountServiceImpl.class);
        assertNotNull(attribute);
        assertEquals(TransactionDefinition.PROPAGATION_MANDATORY, attribute.getPropagationBehavior());
        assertTrue(attribute.rollbackOn(new Exception("ledger insert failed")));
    }

    @Test void participantLocksAreSortedAndDeduplicatedBeforeBusinessChecks() {
        var dao = mock(PointAccountDao.class);
        when(dao.lockBalance(anyString(), anyString())).thenReturn(0);
        var service = new PointAccountServiceImpl(dao, "0", true, "1");
        service.lockAccounts(java.util.List.of("bob", "alice", "bob"));
        var order = inOrder(dao);
        order.verify(dao).ensureAccount(eq("1"), eq("alice"), anyLong());
        order.verify(dao).lockBalance("1", "alice");
        order.verify(dao).ensureAccount(eq("1"), eq("bob"), anyLong());
        order.verify(dao).lockBalance("1", "bob");
        order.verifyNoMoreInteractions();
    }

    @Test void invalidParticipantIsRejectedBeforeAnyLocks() {
        var dao = mock(PointAccountDao.class);
        var service = new PointAccountServiceImpl(dao, "0", false, "");
        assertThrows(IllegalArgumentException.class,
                () -> service.lockAccounts(java.util.List.of("alice", " bad")));
        verifyNoInteractions(dao);
    }

    @Test void eligibilityLocksRequireBusinessTransaction() throws Exception {
        var method = PointAccountServiceImpl.class.getMethod("lockAccounts", java.util.Collection.class);
        var attribute = new AnnotationTransactionAttributeSource()
                .getTransactionAttribute(method, PointAccountServiceImpl.class);
        assertNotNull(attribute);
        assertEquals(TransactionDefinition.PROPAGATION_MANDATORY, attribute.getPropagationBehavior());
    }

    @Test void userPageUsesOneTenantScopedQueryAndDefaultsAbsentAccountsToZero() {
        var dao = mock(PointAccountDao.class);
        when(dao.balances(eq("1"), anyCollection())).thenReturn(java.util.List.of(
                new cn.jia.point.entity.PointAccountBalance("alice", 7)));
        var service = new PointAccountServiceImpl(dao, "0", true, "1");
        assertEquals(java.util.Map.of("alice", 7, "bob", 0),
                service.balances(java.util.List.of("alice", "bob", "alice")));
        verify(dao).balances("1", java.util.Set.of("alice", "bob"));
        verifyNoMoreInteractions(dao);
    }

    @Test void emptyPageDoesNotIssueAnInvalidInClauseOrCreateAccounts() {
        var dao = mock(PointAccountDao.class);
        var service = new PointAccountServiceImpl(dao, "0", false, "");
        assertEquals(java.util.Map.of(), service.balances(java.util.List.of()));
        verifyNoInteractions(dao);
    }
}
