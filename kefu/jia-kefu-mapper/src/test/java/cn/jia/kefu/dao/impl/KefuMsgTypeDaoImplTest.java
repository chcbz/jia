package cn.jia.kefu.dao.impl;

import cn.jia.kefu.dao.KefuMsgTypeDao;
import cn.jia.kefu.entity.KefuMsgTypeEntity;
import cn.jia.test.BaseDbUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class KefuMsgTypeDaoImplTest extends BaseDbUnitTest {
    @Inject
    private KefuMsgTypeDao kefuMsgTypeDao;

    @Test
    void findMsgType() {
        KefuMsgTypeEntity msgType = kefuMsgTypeDao.findMsgType("vote");
        Assertions.assertNotNull(msgType);
        Assertions.assertEquals(msgType.getTypeName(), "每日一题");
    }
}