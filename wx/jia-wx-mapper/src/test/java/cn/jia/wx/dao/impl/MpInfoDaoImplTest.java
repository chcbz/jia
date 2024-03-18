package cn.jia.wx.dao.impl;

import cn.jia.core.util.JsonUtil;
import cn.jia.test.BaseDbUnitTest;
import cn.jia.test.DbUnitHelper;
import cn.jia.wx.dao.MpInfoDao;
import cn.jia.wx.entity.MpInfoEntity;
import com.github.springtestdbunit.annotation.DatabaseOperation;
import com.github.springtestdbunit.annotation.DatabaseSetup;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@Slf4j
class MpInfoDaoImplTest extends BaseDbUnitTest {
    @Inject
    private MpInfoDao mpInfoDao;
    @Value("classpath:testObject/mp_info_init.json")
    private Resource mpInfoJson;

    @Test
    @DatabaseSetup(value = "classpath:testObject/mp_info_init.xml", type = DatabaseOperation.CLEAN_INSERT)
    void findById() {
        MpInfoEntity mpInfoEntity = mpInfoDao.selectById(2);
        assertNotNull(mpInfoEntity);
    }

    @Test
    void save() {
        MpInfoEntity mpInfoEntity = DbUnitHelper.readJsonEntity(mpInfoJson, MpInfoEntity.class);
        mpInfoDao.insert(mpInfoEntity);
        log.info(JsonUtil.toJson(mpInfoEntity));
        assertNotNull(mpInfoEntity);
        assertNotNull(mpInfoEntity.getAcid());
    }

    @Test
    void findByKey() {
        MpInfoEntity mpInfoEntity = mpInfoDao.findByKey("gh_336235a5d843");
        assertNotNull(mpInfoEntity);
        mpInfoEntity = mpInfoDao.findByKey("wxd59557202ddff2d5");
        assertNotNull(mpInfoEntity);
    }
}