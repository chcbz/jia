package cn.jia.wx.service.impl;

import cn.jia.test.BaseMockTest;
import cn.jia.wx.dao.MpInfoDao;
import cn.jia.wx.entity.MpInfoEntity;
import jakarta.servlet.http.HttpServletRequest;
import me.chanjar.weixin.mp.api.WxMpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MpInfoServiceImplTest extends BaseMockTest {
    @InjectMocks
    private MpInfoServiceImpl mpInfoService;
    @Mock
    private MpInfoDao mpInfoDao;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(mpInfoService, "baseDao", mpInfoDao);
    }

    @Test
    void init() {
        MpInfoEntity mpInfoEntity = new MpInfoEntity();
        mpInfoEntity.setAppid("appid");
        when(mpInfoDao.selectAll()).thenReturn(List.of(mpInfoEntity));
        mpInfoService.init();
        WxMpService wxMpService = mpInfoService.findWxMpService("appid");
        assertNotNull(wxMpService);
    }

    @Test
    void findWxMpService() {
        MpInfoEntity mpInfoEntity = new MpInfoEntity();
        mpInfoEntity.setAppid("appid");
        when(mpInfoDao.selectAll()).thenReturn(List.of(mpInfoEntity));
        mpInfoService.init();

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameter("appid")).thenReturn("appid");
        WxMpService wxMpService = mpInfoService.findWxMpService(request);
        assertNotNull(wxMpService);
        assertEquals("appid", wxMpService.getWxMpConfigStorage().getAppId());

        when(request.getParameter("appid")).thenReturn(null);
        Exception exception = assertThrows(Exception.class, () -> mpInfoService.findWxMpService(request));
        assertEquals("appid不能为空", exception.getMessage());
    }

    @Test
    void testFindWxMpService() {
        MpInfoEntity mpInfoEntity = new MpInfoEntity();
        mpInfoEntity.setAppid("appid");
        when(mpInfoDao.selectAll()).thenReturn(List.of(mpInfoEntity));
        mpInfoService.init();

        WxMpService wxMpService = mpInfoService.findWxMpService("appid");
        assertNotNull(wxMpService);
        assertEquals("appid", wxMpService.getWxMpConfigStorage().getAppId());

        Exception exception = assertThrows(Exception.class, () -> mpInfoService.findWxMpService(""));
        assertEquals("appid不能为空", exception.getMessage());
    }

    @Test
    void findByKey() {
        when(mpInfoDao.findByKey("appid")).thenReturn(new MpInfoEntity());
        MpInfoEntity mpInfoEntity = mpInfoService.findByKey("appid");
        assertNotNull(mpInfoEntity);
    }
}