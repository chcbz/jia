package cn.jia.wx.service;

import cn.jia.core.util.JSONUtil;
import cn.jia.test.BaseTest;
import cn.jia.test.DbUnitHelper;
import cn.jia.wx.entity.MpInfo;
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
        MpInfo mpInfo = mpInfoService.getById(1);
        assertNotNull(mpInfo);
    }

    @Test
    void save() {
        MpInfo mpInfo = DbUnitHelper.readJsonEntity(mpInfoJson, MpInfo.class);
        mpInfoService.save(mpInfo);
        log.info(JSONUtil.toJson(mpInfo));
        assertNotNull(mpInfo.getAcid());
    }

}