package cn.jia.wx.service;

import cn.jia.core.util.JsonUtil;
import cn.jia.test.BaseTest;
import cn.jia.test.DbUnitHelper;
import cn.jia.wx.entity.MpInfoEntity;
import com.github.springtestdbunit.annotation.DatabaseOperation;
import com.github.springtestdbunit.annotation.DatabaseSetup;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@Slf4j
public class IMpInfoServiceTest extends BaseTest {
    @Autowired
    private IMpInfoService mpInfoService;
    @Value("classpath:testObject/mp_info_init.json")
    private Resource mpInfoJson;

    @Test
    @DatabaseSetup(value = "classpath:testObject/mp_info_init.xml", type = DatabaseOperation.CLEAN_INSERT)
    void findById() {
        MpInfoEntity mpInfoEntity = mpInfoService.getById(1);
        assertNotNull(mpInfoEntity);
    }

    @Test
    void save() {
        MpInfoEntity mpInfoEntity = DbUnitHelper.readJsonEntity(mpInfoJson, MpInfoEntity.class);
        mpInfoService.save(mpInfoEntity);
        log.info(JsonUtil.toJson(mpInfoEntity));
        assertNotNull(mpInfoEntity.getAcid());
    }

}