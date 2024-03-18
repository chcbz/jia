package cn.jia.point.service.impl;

import cn.jia.point.dao.PointRecordDao;
import cn.jia.point.dao.PointReferralDao;
import cn.jia.point.dao.PointSignDao;
import cn.jia.point.entity.PointRecordEntity;
import cn.jia.point.entity.PointReferralEntity;
import cn.jia.point.entity.PointSignEntity;
import cn.jia.test.BaseMockTest;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.service.UserService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import static org.mockito.Mockito.*;

class PointServiceImplTest extends BaseMockTest {
    @Mock
    PointSignDao pointSignDao;
    @Mock
    PointReferralDao pointReferralDao;
    @Mock
    PointRecordDao pointRecordDao;
    @Mock
    UserService userService;
    @InjectMocks
    PointServiceImpl pointServiceImpl;

    @Test
    void testSign() {
        PointSignEntity latestpointSignEntity = new PointSignEntity();
        latestpointSignEntity.setUpdateTime(-1L);
        when(pointSignDao.selectLatest(anyString())).thenReturn(latestpointSignEntity);
        when(pointSignDao.insert(any())).thenReturn(0);
        when(pointRecordDao.insert(any())).thenReturn(0);
        UserEntity userEntity = new UserEntity();
        userEntity.setPoint(1);
        when(userService.findByJiacn(anyString())).thenReturn(userEntity);
        when(userService.update(any())).thenReturn(userEntity);

        PointSignEntity pointSignEntity = new PointSignEntity();
        pointSignEntity.setJiacn("jiacn");
        PointRecordEntity result = pointServiceImpl.sign(pointSignEntity);
        Assertions.assertEquals("jiacn", result.getJiacn());
    }

    @Test
    void testReferral() {
        when(pointReferralDao.checkHasReferral(anyString())).thenReturn(false);
        when(pointReferralDao.insert(any())).thenReturn(1);
        when(pointRecordDao.insert(any())).thenReturn(1);
        UserEntity userEntity = new UserEntity();
        userEntity.setPoint(1);
        when(userService.findByJiacn(anyString())).thenReturn(userEntity);
        when(userService.update(any())).thenReturn(userEntity);

        PointReferralEntity pointReferralEntity = new PointReferralEntity();
        pointReferralEntity.setReferrer("referrer");
        pointReferralEntity.setReferral("referral");
        PointRecordEntity result = pointServiceImpl.referral(pointReferralEntity);
        Assertions.assertEquals("referrer", result.getJiacn());
    }

    @Test
    void testLuck() {
        when(pointRecordDao.insert(any())).thenReturn(0);
        UserEntity userEntity = new UserEntity();
        userEntity.setPoint(1);
        when(userService.findByJiacn(anyString())).thenReturn(userEntity);

        PointRecordEntity pointRecordEntity = new PointRecordEntity();
        pointRecordEntity.setJiacn("jiacn");
        pointRecordEntity.setChg(1);
        PointRecordEntity result = pointServiceImpl.luck(pointRecordEntity);
        Assertions.assertEquals(pointRecordEntity, result);
    }

    @Test
    void testAdd() {
        when(pointRecordDao.insert(any())).thenReturn(0);
        UserEntity userEntity = new UserEntity();
        userEntity.setPoint(1);
        when(userService.findByJiacn(anyString())).thenReturn(userEntity);

        PointRecordEntity result = pointServiceImpl.add("jiacn", 1, 0);
        Assertions.assertEquals("jiacn", result.getJiacn());
    }

    @Test
    void testInit() {
        when(pointRecordDao.insert(any())).thenReturn(1);
        UserEntity userEntity = new UserEntity();
        userEntity.setPoint(1);
        when(userService.findByJiacn(anyString())).thenReturn(userEntity);

        PointRecordEntity pointRecordEntity = new PointRecordEntity();
        pointRecordEntity.setJiacn("jiacn");
        PointRecordEntity result = pointServiceImpl.init(pointRecordEntity);
        Assertions.assertEquals(pointRecordEntity, result);
    }
}
