package cn.jia.wx.dao.impl;

import cn.jia.test.BaseDbUnitTest;
import cn.jia.wx.dao.MpUserDao;
import cn.jia.wx.entity.MpUserEntity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MpUserDaoImplTest extends BaseDbUnitTest {
    @Inject
    private MpUserDao mpUserDao;
    @Inject
    private JdbcTemplate jdbcTemplate;

    @Test
    void touchLastActiveKeepsTheNewestVerifiedCallbackTime() {
        assertEquals(1, mpUserDao.touchLastActive(1L, 1720000000L));
        assertEquals(1720000000L, jdbcTemplate.queryForObject(
                "SELECT last_active_time FROM wx_mp_user WHERE id = 1", Long.class));

        assertEquals(1, mpUserDao.touchLastActive(1L, 1710000000L));
        assertEquals(1720000000L, jdbcTemplate.queryForObject(
                "SELECT last_active_time FROM wx_mp_user WHERE id = 1", Long.class));
    }

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