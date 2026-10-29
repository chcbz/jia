package cn.jia.point.service.impl;

import cn.jia.point.common.PointConstants;
import cn.jia.point.dao.PointGiftDao;
import cn.jia.point.dao.PointGiftUsageDao;
import cn.jia.point.dao.PointRecordDao;
import cn.jia.point.entity.PointGiftEntity;
import cn.jia.point.entity.PointGiftUsageEntity;
import cn.jia.point.entity.PointGiftVO;
import cn.jia.test.BaseMockTest;
import cn.jia.point.service.PointAccountService;
import cn.jia.point.entity.PointRecordEntity;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.service.UserService;
import com.github.pagehelper.PageInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;

import static org.mockito.Mockito.*;

class GiftServiceImplTest extends BaseMockTest {
    @Mock
    PointGiftDao pointGiftDao;
    @Mock
    PointGiftUsageDao pointGiftUsageDao;
    @Mock
    UserService userService;
    @Mock
    PointRecordDao pointRecordDao;
    @Mock
    PointAccountService accounts;
    @BeforeEach
    void scope() { lenient().when(accounts.tenantId()).thenReturn("1"); }
    @InjectMocks
    GiftServiceImpl giftServiceImpl;

    @Test
    void testCreate() {
        when(pointGiftDao.insert(any())).thenReturn(1);

        PointGiftEntity pointGiftEntity = new PointGiftEntity();
        PointGiftEntity result = giftServiceImpl.create(pointGiftEntity);
        Assertions.assertEquals(pointGiftEntity, result);
    }

    @Test
    void testFind() {
        PointGiftEntity pointGiftEntity = new PointGiftEntity();
        when(pointGiftDao.selectScoped(eq("1"), any(), anyBoolean())).thenReturn(pointGiftEntity);

        PointGiftEntity result = giftServiceImpl.find(1L);
        Assertions.assertEquals(pointGiftEntity, result);
    }

    @Test
    void testUpdate() {
        when(pointGiftDao.updateScoped(eq("1"), any())).thenReturn(1);

        PointGiftEntity pointGiftEntity = new PointGiftEntity();
        PointGiftEntity result = giftServiceImpl.update(pointGiftEntity);
        Assertions.assertEquals(pointGiftEntity, result);
    }

    @Test
    void testDelete() {
        when(pointGiftDao.deleteScoped(eq("1"), any())).thenReturn(0);

        giftServiceImpl.delete(1L);
    }

    @Test
    void testList() {
        when(pointGiftDao.listScoped(eq("1"), any())).thenReturn(List.of(new PointGiftEntity()));

        PageInfo<PointGiftEntity> result = giftServiceImpl.list(1, 1, new PointGiftVO(), null);
        Assertions.assertEquals(1, result.getSize());
    }

    @Test
    void testUsage() {
        when(pointGiftDao.updateScoped(eq("1"), any())).thenReturn(1);
        PointGiftEntity pointGiftEntity = new PointGiftEntity();
        pointGiftEntity.setQuantity(2);
        pointGiftEntity.setPoint(2);
        when(pointGiftDao.selectScoped(eq("1"), any(), anyBoolean())).thenReturn(pointGiftEntity);
        when(pointGiftUsageDao.insert(any())).thenReturn(1);
        UserEntity userEntity = new UserEntity();
        userEntity.setPoint(10);
        when(userService.findOwnIdentityByExactJiacn(anyString())).thenReturn(userEntity);
        when(pointRecordDao.insert(any())).thenReturn(1);

        PointGiftUsageEntity pointGiftUsageEntity = new PointGiftUsageEntity();
        pointGiftUsageEntity.setJiacn("jia_client");
        pointGiftUsageEntity.setQuantity(1);
        pointGiftUsageEntity.setGiftId(1L);
        giftServiceImpl.usage(pointGiftUsageEntity);
    }

    @Test
    void testUsageCancel() {
        when(pointGiftUsageDao.updateScoped(eq("1"), any())).thenReturn(1);
        PointGiftUsageEntity pointGiftUsageEntity = new PointGiftUsageEntity();
        pointGiftUsageEntity.setStatus(PointConstants.GIFT_USAGE_STATUS_PAYED);
        pointGiftUsageEntity.setJiacn("jia");
        pointGiftUsageEntity.setPoint(1);
        when(pointGiftUsageDao.selectScoped(eq("1"), any(), eq(true))).thenReturn(pointGiftUsageEntity);
        UserEntity userEntity = new UserEntity();
        userEntity.setPoint(2);
        when(userService.findOwnIdentityByExactJiacn(anyString())).thenReturn(userEntity);
        when(pointRecordDao.insert(any())).thenReturn(1);
        when(accounts.changeAndGet("jia", 1)).thenReturn(3);

        giftServiceImpl.usageCancel(1L);
        verify(accounts).changeAndGet("jia", 1);
        verify(userService, never()).update(any());
        ArgumentCaptor<PointRecordEntity> ledger = ArgumentCaptor.forClass(PointRecordEntity.class);
        verify(pointRecordDao).insert(ledger.capture());
        Assertions.assertEquals("1", ledger.getValue().getTenantId());
        Assertions.assertEquals(3, ledger.getValue().getRemain());
        Assertions.assertEquals(1, ledger.getValue().getChg());
    }

    @Test
    void testUsageDelete() {
        PointGiftEntity pointGiftEntity = new PointGiftEntity();
        pointGiftEntity.setStatus(PointConstants.GIFT_USAGE_STATUS_DRAFT);
        when(pointGiftUsageDao.deleteScoped(eq("1"), any())).thenReturn(1);
        PointGiftUsageEntity pointGiftUsageEntity = new PointGiftUsageEntity();
        pointGiftUsageEntity.setStatus(PointConstants.GIFT_USAGE_STATUS_DRAFT);
        when(pointGiftUsageDao.selectScoped(eq("1"), any(), eq(true))).thenReturn(pointGiftUsageEntity);

        giftServiceImpl.usageDelete(1L);
    }

    @Test
    void testUsageListByGift() {
        when(pointGiftUsageDao.listByGift(eq("1"), anyLong())).thenReturn(List.of(new PointGiftUsageEntity()));

        PageInfo<PointGiftUsageEntity> result = giftServiceImpl.usageListByGift(1, 1, 1L, null);
        Assertions.assertEquals(1, result.getSize());
    }

    @Test
    void testUsageListByUser() {
        when(pointGiftUsageDao.listByUser(eq("1"), anyString())).thenReturn(List.of(new PointGiftUsageEntity()));

        PageInfo<PointGiftUsageEntity> result = giftServiceImpl.usageListByUser(1, 1, "jiacn", null);
        Assertions.assertEquals(1, result.getSize());
    }

    @Test void missingOrOtherTenantUsageCannotRefund() {
        Assertions.assertThrows(cn.jia.core.exception.EsRuntimeException.class,
                () -> giftServiceImpl.usageCancel(42L));
        verify(pointGiftUsageDao).selectScoped("1", 42L, true);
        verify(accounts, never()).changeAndGet(anyString(), anyInt());
        verifyNoInteractions(pointRecordDao);
    }

    @Test void alreadyCanceledUsageCannotRefundTwice() {
        PointGiftUsageEntity usage = new PointGiftUsageEntity();
        usage.setStatus(PointConstants.GIFT_USAGE_STATUS_CANCEL);
        when(pointGiftUsageDao.selectScoped("1", 42L, true)).thenReturn(usage);
        Assertions.assertThrows(cn.jia.core.exception.EsRuntimeException.class,
                () -> giftServiceImpl.usageCancel(42L));
        verify(accounts, never()).changeAndGet(anyString(), anyInt());
    }

    @Test void refundLedgerFailurePreventsSuccessAndStatusUpdate() {
        PointGiftUsageEntity usage = new PointGiftUsageEntity();
        usage.setStatus(PointConstants.GIFT_USAGE_STATUS_PAYED);
        usage.setJiacn("alice"); usage.setPoint(4);
        when(pointGiftUsageDao.selectScoped("1", 42L, true)).thenReturn(usage);
        when(userService.findOwnIdentityByExactJiacn("alice")).thenReturn(new UserEntity());
        Assertions.assertThrows(IllegalStateException.class, () -> giftServiceImpl.usageCancel(42L));
        verify(accounts).changeAndGet("alice", 4);
        verify(pointGiftUsageDao, never()).updateScoped(anyString(), any());
    }

    @Test void failedCancellationStatusThrowsForTransactionRollback() {
        PointGiftUsageEntity usage = new PointGiftUsageEntity();
        usage.setStatus(PointConstants.GIFT_USAGE_STATUS_PAYED);
        usage.setJiacn("alice"); usage.setPoint(4);
        when(pointGiftUsageDao.selectScoped("1", 42L, true)).thenReturn(usage);
        when(userService.findOwnIdentityByExactJiacn("alice")).thenReturn(new UserEntity());
        when(pointRecordDao.insert(any())).thenReturn(1);
        Assertions.assertThrows(IllegalStateException.class, () -> giftServiceImpl.usageCancel(42L));
        verify(accounts).changeAndGet("alice", 4);
    }

    @Test void zeroQuantityCannotCreditOrIncreaseStock() {
        PointGiftEntity gift = new PointGiftEntity(); gift.setQuantity(5);
        when(pointGiftDao.selectScoped("1", 1L, true)).thenReturn(gift);
        PointGiftUsageEntity usage = new PointGiftUsageEntity();
        usage.setGiftId(1L); usage.setQuantity(0);
        Assertions.assertThrows(IllegalArgumentException.class, () -> giftServiceImpl.usage(usage));
        verify(pointGiftDao, never()).updateScoped(anyString(), any());
        verify(accounts, never()).changeAndGet(anyString(), anyInt());
    }

    @Test void cashOrderCannotInjectRefundablePoints() {
        PointGiftEntity gift = new PointGiftEntity(); gift.setQuantity(5);
        when(pointGiftDao.selectScoped("1", 1L, true)).thenReturn(gift);
        when(pointGiftDao.updateScoped(eq("1"), any())).thenReturn(1);
        when(pointGiftUsageDao.insert(any())).thenReturn(1);
        when(userService.findOwnIdentityByExactJiacn("alice")).thenReturn(new UserEntity());
        PointGiftUsageEntity usage = new PointGiftUsageEntity();
        usage.setGiftId(1L); usage.setQuantity(1); usage.setJiacn("alice");
        usage.setPrice(100); usage.setPoint(999); usage.setTenantId("0");
        giftServiceImpl.usage(usage);
        Assertions.assertEquals(0, usage.getPoint());
        Assertions.assertEquals("1", usage.getTenantId());
        verify(accounts, never()).changeAndGet(anyString(), anyInt());
    }
}
