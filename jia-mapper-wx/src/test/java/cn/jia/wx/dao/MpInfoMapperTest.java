package cn.jia.wx.dao;

import cn.jia.test.BaseTest;
import cn.jia.wx.entity.MpInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MpInfoMapperTest extends BaseTest {
    @Autowired
    private MpInfoMapper mpInfoMapper;

    @Test
    void selectAll() {
        List<MpInfo> mpInfoList = mpInfoMapper.selectAll();
        assertTrue(mpInfoList.size() > 0);
    }

    @Test
    void selectByExample() {
    }

    @Test
    void selectByKey() {
    }
}