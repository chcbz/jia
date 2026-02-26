package cn.jia.wx.dao.impl;

import cn.jia.core.util.JsonUtil;
import cn.jia.test.BaseDbUnitTest;
import cn.jia.test.DbUnitHelper;
import cn.jia.wx.dao.MpInfoDao;
import cn.jia.wx.entity.MpInfoEntity;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.test.context.jdbc.Sql;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@Slf4j
class MpInfoDaoImplTest extends BaseDbUnitTest {
    @Inject
    private MpInfoDao mpInfoDao;
    @Value("classpath:testObject/mp_info_init.json")
    private Resource mpInfoJson;

    @Test
    @Sql(scripts = "/testObject/mp_info_init.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
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