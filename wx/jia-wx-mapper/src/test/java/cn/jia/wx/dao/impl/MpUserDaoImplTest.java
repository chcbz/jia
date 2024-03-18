package cn.jia.wx.dao.impl;

import cn.jia.test.BaseDbUnitTest;
import cn.jia.wx.dao.MpUserDao;
import cn.jia.wx.entity.MpUserEntity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MpUserDaoImplTest extends BaseDbUnitTest {
    @Inject
    private MpUserDao mpUserDao;

    @Test
    void unsubscribe() {
        MpUserEntity mpUserEntity = new MpUserEntity();
        mpUserEntity.setAppid("wxd59557202ddff2d5");
        int unsubscribe = mpUserDao.unsubscribe(mpUserEntity);
        assertEquals(1, unsubscribe);

        mpUserDao.selectByEntity(mpUserEntity).forEach(mpUserEntity1 ->
                assertEquals(0, mpUserEntity1.getSubscribe()));
    }
}