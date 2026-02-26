package cn.jia.point.service.impl;

import cn.jia.point.common.PointConstants;
import cn.jia.point.dao.PointGiftDao;
import cn.jia.point.dao.PointGiftUsageDao;
import cn.jia.point.dao.PointRecordDao;
import cn.jia.point.entity.PointGiftEntity;
import cn.jia.point.entity.PointGiftUsageEntity;
import cn.jia.point.entity.PointGiftVO;
import cn.jia.test.BaseMockTest;
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
    @InjectMocks
    GiftServiceImpl giftServiceImpl;

    @Test
    void testCreate() {
        when(pointGiftDao.insert(any())).thenReturn(0);

        PointGiftEntity pointGiftEntity = new PointGiftEntity();
        PointGiftEntity result = giftServiceImpl.create(pointGiftEntity);
        Assertions.assertEquals(pointGiftEntity, result);
    }

    @Test
    void testFind() {
        PointGiftEntity pointGiftEntity = new PointGiftEntity();
        when(pointGiftDao.selectById(any())).thenReturn(pointGiftEntity);

        PointGiftEntity result = giftServiceImpl.find(1L);
        Assertions.assertEquals(pointGiftEntity, result);
    }

    @Test
    void testUpdate() {
        when(pointGiftDao.updateById(any())).thenReturn(0);

        PointGiftEntity pointGiftEntity = new PointGiftEntity();
        PointGiftEntity result = giftServiceImpl.update(pointGiftEntity);
        Assertions.assertEquals(pointGiftEntity, result);
    }

    @Test
    void testDelete() {
        when(pointGiftDao.deleteById(any())).thenReturn(0);

        giftServiceImpl.delete(1L);
    }

    @Test
    void testList() {
        when(pointGiftDao.selectByEntity(any())).thenReturn(List.of(new PointGiftEntity()));

        PageInfo<PointGiftEntity> result = giftServiceImpl.list(1, 1, new PointGiftVO(), null);
        Assertions.assertEquals(1, result.getSize());
    }

    @Test
    void testUsage() {
        when(pointGiftDao.updateById(any())).thenReturn(1);
        PointGiftEntity pointGiftEntity = new PointGiftEntity();
        pointGiftEntity.setQuantity(2);
        pointGiftEntity.setPoint(2);
        when(pointGiftDao.selectById(any())).thenReturn(pointGiftEntity);
        when(pointGiftUsageDao.insert(any())).thenReturn(1);
        UserEntity userEntity = new UserEntity();
        userEntity.setPoint(10);
        when(userService.findByJiacn(anyString())).thenReturn(userEntity);
        when(pointRecordDao.insert(any())).thenReturn(1);

        PointGiftUsageEntity pointGiftUsageEntity = new PointGiftUsageEntity();
        pointGiftUsageEntity.setJiacn("jia_client");
        pointGiftUsageEntity.setQuantity(1);
        pointGiftUsageEntity.setGiftId(1L);
        giftServiceImpl.usage(pointGiftUsageEntity);
    }

    @Test
    void testUsageCancel() {
        when(pointGiftUsageDao.updateById(any())).thenReturn(0);
        PointGiftUsageEntity pointGiftUsageEntity = new PointGiftUsageEntity();
        pointGiftUsageEntity.setStatus(PointConstants.GIFT_USAGE_STATUS_PAYED);
        pointGiftUsageEntity.setJiacn("jia");
        pointGiftUsageEntity.setPoint(1);
        when(pointGiftUsageDao.selectById(any())).thenReturn(pointGiftUsageEntity);
        UserEntity userEntity = new UserEntity();
        userEntity.setPoint(2);
        when(userService.findByJiacn(anyString())).thenReturn(userEntity);
        when(userService.update(any())).thenReturn(userEntity);

        giftServiceImpl.usageCancel(1L);
    }

    @Test
    void testUsageDelete() {
        PointGiftEntity pointGiftEntity = new PointGiftEntity();
        pointGiftEntity.setStatus(PointConstants.GIFT_USAGE_STATUS_DRAFT);
        when(pointGiftUsageDao.deleteById(any())).thenReturn(0);
        PointGiftUsageEntity pointGiftUsageEntity = new PointGiftUsageEntity();
        pointGiftUsageEntity.setStatus(PointConstants.GIFT_USAGE_STATUS_DRAFT);
        when(pointGiftUsageDao.selectById(any())).thenReturn(pointGiftUsageEntity);

        giftServiceImpl.usageDelete(1L);
    }

    @Test
    void testUsageListByGift() {
        when(pointGiftUsageDao.listByGift(anyLong())).thenReturn(List.of(new PointGiftUsageEntity()));

        PageInfo<PointGiftUsageEntity> result = giftServiceImpl.usageListByGift(1, 1, 1L, null);
        Assertions.assertEquals(1, result.getSize());
    }

    @Test
    void testUsageListByUser() {
        when(pointGiftUsageDao.listByUser(anyString())).thenReturn(List.of(new PointGiftUsageEntity()));

        PageInfo<PointGiftUsageEntity> result = giftServiceImpl.usageListByUser(1, 1, "jiacn", null);
        Assertions.assertEquals(1, result.getSize());
    }
}