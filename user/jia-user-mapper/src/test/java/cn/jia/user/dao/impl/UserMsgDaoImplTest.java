package cn.jia.user.dao.impl;

import cn.jia.test.BaseDbUnitTest;
import cn.jia.user.entity.MsgEntity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserMsgDaoImplTest extends BaseDbUnitTest {
    @Inject
    UserMsgDaoImpl userMsgDao;

    @Test
    void updateByUserId() {
        MsgEntity entity = new MsgEntity();
        entity.setUserId(1L);
        entity.setStatus(1);
        int i = userMsgDao.updateByUserId(entity);
        assertEquals(1, i);
    }
}