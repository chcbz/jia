package cn.jia.sms.dao.impl;

import cn.jia.sms.dao.SmsConfigDao;
import cn.jia.test.BaseDbUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SmsConfigDaoImplTest extends BaseDbUnitTest {
    @Inject
    private SmsConfigDao smsConfigDao;

    @Test
    void reduce() {
        int result = smsConfigDao.reduce("jia_client");
        assertEquals(1, result);
    }
}