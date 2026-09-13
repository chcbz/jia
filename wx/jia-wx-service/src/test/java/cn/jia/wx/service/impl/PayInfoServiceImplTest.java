package cn.jia.wx.service.impl;

import cn.jia.test.BaseMockTest;
import cn.jia.wx.dao.PayInfoDao;
import cn.jia.wx.entity.PayInfoEntity;
import com.github.binarywang.wxpay.config.WxPayConfig;
import com.github.binarywang.wxpay.service.WxPayService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PayInfoServiceImplTest extends BaseMockTest {
    @Mock
    private PayInfoDao payInfoDao;

    @InjectMocks
    private PayInfoServiceImpl payInfoService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(payInfoService, "baseDao", payInfoDao);
    }

    @Test
    void init() {
        PayInfoEntity payInfoEntity = new PayInfoEntity();
        payInfoEntity.setAppId("appid");
        payInfoEntity.setMchId("mchId");
        when(payInfoDao.selectAll()).thenReturn(List.of(payInfoEntity));
        payInfoService.init();
        WxPayService wxPayService = payInfoService.findWxPayService("appid");
        assertNotNull(wxPayService);
        WxPayConfig sdkDefaults = new WxPayConfig();
        assertEquals(sdkDefaults.getHttpConnectionTimeout(),
                wxPayService.getConfig().getHttpConnectionTimeout());
        assertEquals(sdkDefaults.getHttpTimeout(), wxPayService.getConfig().getHttpTimeout());
    }

    @Test
    void appliesOnlyExplicitPositiveTimeoutOverrides() {
        ReflectionTestUtils.setField(payInfoService, "connectTimeoutMillis", 7001);
        ReflectionTestUtils.setField(payInfoService, "readTimeoutMillis", 12001);
        PayInfoEntity payInfoEntity = new PayInfoEntity();
        payInfoEntity.setAppId("explicit-appid");
        payInfoEntity.setMchId("mchId");
        when(payInfoDao.selectAll()).thenReturn(List.of(payInfoEntity));

        payInfoService.init();

        WxPayService wxPayService = payInfoService.findWxPayService("explicit-appid");
        assertEquals(7001, wxPayService.getConfig().getHttpConnectionTimeout());
        assertEquals(12001, wxPayService.getConfig().getHttpTimeout());
    }

    @Test
    void findWxPayService() {
        PayInfoEntity payInfoEntity = new PayInfoEntity();
        payInfoEntity.setAppId("appid");
        payInfoEntity.setMchId("mchId");
        when(payInfoDao.selectAll()).thenReturn(List.of(payInfoEntity));
        payInfoService.init();

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameter("appid")).thenReturn("appid");
        WxPayService wxMpService = payInfoService.findWxPayService(request);
        assertNotNull(wxMpService);
        assertEquals("appid", wxMpService.getConfig().getAppId());

        when(request.getParameter("appid")).thenReturn(null);
        Exception exception = assertThrows(Exception.class, () -> payInfoService.findWxPayService(request));
        assertEquals("appid不能为空", exception.getMessage());
    }

    @Test
    void testFindWxPayService() {
        PayInfoEntity payInfoEntity = new PayInfoEntity();
        payInfoEntity.setAppId("appid");
        payInfoEntity.setMchId("mchId");
        when(payInfoDao.selectAll()).thenReturn(List.of(payInfoEntity));
        payInfoService.init();

        WxPayService wxMpService = payInfoService.findWxPayService("appid");
        assertNotNull(wxMpService);
        assertEquals("appid", wxMpService.getConfig().getAppId());

        Exception exception = assertThrows(Exception.class, () -> payInfoService.findWxPayService(""));
        assertEquals("appid不能为空", exception.getMessage());
    }

    @Test
    void findByKey() {
        when(payInfoDao.selectByEntity(any())).thenReturn(Collections.singletonList(new PayInfoEntity()));
        PayInfoEntity payInfoEntity = payInfoService.findByKey("appid");
        assertNotNull(payInfoEntity);
    }
}