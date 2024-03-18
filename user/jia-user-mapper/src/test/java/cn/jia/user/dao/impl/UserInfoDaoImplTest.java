package cn.jia.user.dao.impl;

import cn.jia.test.BaseDbUnitTest;
import cn.jia.user.entity.UserEntity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UserInfoDaoImplTest extends BaseDbUnitTest {
    @Inject
    UserInfoDaoImpl userInfoDao;

    @Test
    void selectByJiacn() {
        UserEntity jiacn = userInfoDao.selectByJiacn("oH2zD1El9hvjnWu-LRmCr-JiTuXI");
        assertNotNull(jiacn);
    }

    @Test
    void selectByUsername() {
        UserEntity username = userInfoDao.selectByUsername("oH2zD1El9hvjnWu-LRmCr-JiTuXI");
        assertNotNull(username);
    }

    @Test
    void selectByOpenid() {
        UserEntity openid = userInfoDao.selectByOpenid("oH2zD1El9hvjnWu-LRmCr-JiTuXI");
        assertNotNull(openid);
    }

    @Test
    void selectByPhone() {
        UserEntity phone = userInfoDao.selectByPhone("13450909878");
        assertNotNull(phone);
    }

    @Test
    void selectByRole() {
        List<UserEntity> userEntities = userInfoDao.selectByRole(1L);
        assertNotNull(userEntities);
        assertEquals(1, userEntities.size());
    }

    @Test
    void selectByGroup() {
        List<UserEntity> userEntities = userInfoDao.selectByGroup(1L);
        assertNotNull(userEntities);
        assertEquals(1, userEntities.size());
    }

    @Test
    void selectByOrg() {
        List<UserEntity> userEntities = userInfoDao.selectByOrg(1L);
        assertNotNull(userEntities);
        assertEquals(1, userEntities.size());
    }

    @Test
    void searchByExample() {
        UserEntity user = new UserEntity();
        user.setJiacn("oH2zD1El9hvjnWu-LRmCr-JiTuXI");
        List<UserEntity> userEntities = userInfoDao.searchByExample(user);
        assertNotNull(userEntities);
        assertEquals(1, userEntities.size());
    }
}