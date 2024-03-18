package cn.jia.point.dao.impl;

import cn.jia.point.dao.PointReferralDao;
import cn.jia.test.BaseDbUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PointReferralDaoImplTest extends BaseDbUnitTest {
    @Inject
    private PointReferralDao pointReferralDao;

    @Test
    void checkHasReferral() {
        boolean b = pointReferralDao.checkHasReferral("o-q51v7nN6nXQ-ciwOiRFRuef-_Q");
        assertTrue(b);
    }
}