package cn.jia.wx.dao;

import cn.jia.test.BaseTest;
import cn.jia.wx.entity.MpInfo;
import cn.jia.wx.entity.MpInfoExample;
import com.github.pagehelper.Page;
import com.github.springtestdbunit.annotation.DatabaseOperation;
import com.github.springtestdbunit.annotation.DatabaseSetup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MpInfoMapperTest extends BaseTest {
    @Autowired
    private MpInfoMapper mpInfoMapper;

    @Test
    @DatabaseSetup(value = "classpath:testObject/mp_info_init.xml", type = DatabaseOperation.CLEAN_INSERT)
    void selectAll() {
        List<MpInfo> mpInfoList = mpInfoMapper.selectAll();
        assertTrue(mpInfoList.size() > 0);
    }

    @Test
    @DatabaseSetup(value = "classpath:testObject/mp_info_init.xml", type = DatabaseOperation.CLEAN_INSERT)
    void selectByExample() {
        MpInfoExample mpInfoExample = new MpInfoExample();
        mpInfoExample.setClientId("jia_client");
        Page<MpInfo> mpInfoMapperPage = mpInfoMapper.selectByExample(mpInfoExample);
        assertTrue(mpInfoMapperPage.getResult().size() > 0);
    }

    @Test
    @DatabaseSetup(value = "classpath:testObject/mp_info_init.xml", type = DatabaseOperation.CLEAN_INSERT)
    void selectByKey() {
        MpInfo mpInfo = mpInfoMapper.selectByKey("wxd59557202ddff2d5");
        assertNotNull(mpInfo);
    }
}