package cn.jia.sms.dao.impl;

import cn.jia.sms.dao.SmsBuyDao;
import cn.jia.sms.entity.SmsBuyEntity;
import cn.jia.test.BaseDbUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmsBuyDaoImplTest extends BaseDbUnitTest {
    @Inject
    private SmsBuyDao smsBuyDao;

    @Test
    void selectLatest() {
        SmsBuyEntity entity = smsBuyDao.selectLatest("jianO8sbRLofO5XcBxL");
        assertEquals(1L, entity.getId());
    }
}