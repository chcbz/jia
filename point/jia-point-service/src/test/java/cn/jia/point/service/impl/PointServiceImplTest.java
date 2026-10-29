package cn.jia.point.service.impl;

import cn.jia.core.exception.EsRuntimeException;
import cn.jia.point.common.PointConstants;
import cn.jia.point.dao.PointRecordDao;
import cn.jia.point.dao.PointReferralDao;
import cn.jia.point.dao.PointSignDao;
import cn.jia.point.entity.PointRecordEntity;
import cn.jia.point.entity.PointReferralEntity;
import cn.jia.point.entity.PointSignEntity;
import cn.jia.point.service.PointAccountService;
import cn.jia.test.BaseMockTest;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PointServiceImplTest extends BaseMockTest {
    @Mock PointSignDao pointSignDao;
    @Mock PointReferralDao pointReferralDao;
    @Mock PointRecordDao pointRecordDao;
    @Mock UserService userService;
    @Mock PointAccountService accounts;
    @InjectMocks PointServiceImpl service;

    private void user(String jiacn) {
        UserEntity identity = new UserEntity();
        identity.setPoint(999); // stale legacy column must never become authoritative
        when(userService.findOwnIdentityByExactJiacn(jiacn)).thenReturn(identity);
    }

    @Test void signLocksEligibilityAndUsesAccountResult() {
        user("alice");
        when(accounts.tenantId()).thenReturn("1");
        when(accounts.changeAndGet("alice", PointConstants.POINT_SCORE_SIGN)).thenReturn(7);
        when(pointRecordDao.insert(any())).thenReturn(1);
        when(pointSignDao.insert(any())).thenReturn(1);
        PointSignEntity sign = new PointSignEntity();
        sign.setJiacn("alice");
        sign.setTenantId("0");
        PointRecordEntity result = service.sign(sign);
        assertEquals(7, result.getRemain());
        assertEquals("1", result.getTenantId());
        assertEquals("1", sign.getTenantId());
        var order = inOrder(accounts, pointSignDao);
        order.verify(accounts).lockAccounts(List.of("alice"));
        order.verify(accounts).tenantId();
        order.verify(pointSignDao).selectLatest("1", "alice");
        order.verify(accounts).changeAndGet("alice", PointConstants.POINT_SCORE_SIGN);
        verify(userService, never()).changePoint(anyString(), anyInt());
    }

    @Test void signFailureIsNotReportedAsSuccess() {
        user("alice");
        when(pointRecordDao.insert(any())).thenReturn(1);
        PointSignEntity sign = new PointSignEntity();
        sign.setJiacn("alice");
        assertThrows(IllegalStateException.class, () -> service.sign(sign));
    }

    @Test void referralLocksBothIdentitiesBeforeCheckingTenantEligibility() {
        user("bob"); user("alice");
        when(accounts.tenantId()).thenReturn("1");
        when(accounts.changeAndGet("bob", PointConstants.POINT_SCORE_REFERRAL)).thenReturn(9);
        when(pointReferralDao.insert(any())).thenReturn(1);
        when(pointRecordDao.insert(any())).thenReturn(1);
        PointReferralEntity referral = new PointReferralEntity();
        referral.setReferrer("bob"); referral.setReferral("alice");
        var result = service.referral(referral);
        assertEquals(9, result.getRemain());
        assertEquals("1", referral.getTenantId());
        var order = inOrder(accounts, pointReferralDao);
        order.verify(accounts).lockAccounts(List.of("bob", "alice"));
        order.verify(accounts).tenantId();
        order.verify(pointReferralDao).checkHasReferral("1", "alice");
        order.verify(accounts).changeAndGet("bob", PointConstants.POINT_SCORE_REFERRAL);
    }

    @Test void alreadyReferredDoesNotCreditAgain() {
        user("bob"); user("alice");
        when(accounts.tenantId()).thenReturn("1");
        when(pointReferralDao.checkHasReferral("1", "alice")).thenReturn(true);
        PointReferralEntity referral = new PointReferralEntity();
        referral.setReferrer("bob"); referral.setReferral("alice");
        assertThrows(EsRuntimeException.class, () -> service.referral(referral));
        verify(accounts, never()).changeAndGet(anyString(), anyInt());
    }

    @Test void luckUsesReturnedBalancesRatherThanUserProjection() {
        user("alice");
        when(pointRecordDao.insert(any())).thenReturn(1);
        when(accounts.changeAndGet(eq("alice"), anyInt())).thenReturn(8, 12);
        PointRecordEntity record = new PointRecordEntity();
        record.setJiacn("alice"); record.setChg(1);
        assertSame(record, service.luck(record));
        assertEquals(12, record.getRemain());
        verify(accounts).changeAndGet("alice", -1);
        verify(pointRecordDao, times(2)).insert(any());
    }

    @Test void negativeStakeCannotCreditAccount() {
        user("alice");
        PointRecordEntity record = new PointRecordEntity();
        record.setJiacn("alice"); record.setChg(-1);
        assertThrows(IllegalArgumentException.class, () -> service.luck(record));
        verifyNoInteractions(accounts, pointRecordDao);
    }

    @Test void addUsesPointDomainAndPersistsScopedLedger() {
        user("alice");
        when(accounts.tenantId()).thenReturn("1");
        when(accounts.changeAndGet("alice", 1)).thenReturn(2);
        when(pointRecordDao.insert(any())).thenReturn(1);
        var result = service.add("alice", 1, 0);
        assertEquals(2, result.getRemain());
        assertEquals("1", result.getTenantId());
        verify(userService, never()).changePointAndGet(anyString(), anyInt());
    }

    @Test void failedLedgerThrowsSoOuterTransactionRollsBack() {
        user("alice");
        assertThrows(IllegalStateException.class, () -> service.add("alice", 1, 0));
    }

    @Test void missingUserCannotCreatePointAccount() {
        assertThrows(EsRuntimeException.class, () -> service.add("missing", 1, 0));
        verifyNoInteractions(accounts, pointRecordDao);
    }

    @Test void initUsesInitLedgerTypeAndAccountBalance() {
        user("alice");
        when(pointRecordDao.insert(any())).thenReturn(1);
        when(accounts.changeAndGet("alice", PointConstants.POINT_SCORE_INIT)).thenReturn(10);
        PointRecordEntity record = new PointRecordEntity(); record.setJiacn("alice");
        assertSame(record, service.init(record));
        assertEquals(PointConstants.POINT_TYPE_INIT, record.getType());
        assertEquals(10, record.getRemain());
    }
}
