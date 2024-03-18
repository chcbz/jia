package cn.jia.point.dao.impl;

import cn.jia.point.dao.PointGiftUsageDao;
import cn.jia.point.entity.PointGiftUsageEntity;
import cn.jia.test.BaseDbUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PointGiftUsageDaoImplTest extends BaseDbUnitTest {
    @Inject
    private PointGiftUsageDao pointGiftUsageDao;

    @Test
    void listByUser() {
        List<PointGiftUsageEntity> usageEntities = pointGiftUsageDao.listByUser("oH2zD1PUPvspicVak69uB4wDaFLg");
        assertEquals(1, usageEntities.size());
        assertEquals(7L, usageEntities.get(0).getGiftId());
    }

    @Test
    void listByGift() {
        List<PointGiftUsageEntity> pointGiftUsageEntities = pointGiftUsageDao.listByGift(7L);
        assertEquals(1, pointGiftUsageEntities.size());
        assertEquals(7L, pointGiftUsageEntities.get(0).getGiftId());
    }
}