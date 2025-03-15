package cn.jia.kefu.service.impl;

import cn.jia.core.common.EsConstants;
import cn.jia.core.redis.RedisService;
import cn.jia.kefu.dao.KefuMsgLogDao;
import cn.jia.kefu.dao.KefuMsgSubscribeDao;
import cn.jia.kefu.dao.KefuMsgTypeDao;
import cn.jia.kefu.entity.KefuMsgSubscribeEntity;
import cn.jia.kefu.entity.KefuMsgTypeCode;
import cn.jia.kefu.entity.KefuMsgTypeEntity;
import cn.jia.test.BaseMockTest;
import cn.jia.wx.entity.MpTemplateEntity;
import cn.jia.wx.entity.MpUserEntity;
import cn.jia.wx.service.MpInfoService;
import cn.jia.wx.service.MpTemplateService;
import cn.jia.wx.service.MpUserService;
import me.chanjar.weixin.mp.api.WxMpKefuService;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.api.WxMpTemplateMsgService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.slf4j.Logger;

import java.util.List;

import static org.mockito.Mockito.*;

class KefuServiceImplTest extends BaseMockTest {
    @Mock
    KefuMsgTypeDao kefuMsgTypeDao;
    @Mock
    KefuMsgSubscribeDao kefuMsgSubscribeDao;
    @Mock
    KefuMsgLogDao kefuMsgLogDao;
    @Mock
    MpInfoService mpInfoService;
    @Mock
    MpTemplateService mpTemplateService;
    @Mock
    MpUserService mpUserService;
    @Mock
    RedisService redisService;
    @Mock
    Logger log;
    @InjectMocks
    KefuServiceImpl kefuServiceImpl;

    @Test
    void testSendMessage() throws Exception {
        when(kefuMsgTypeDao.findMsgType(anyString())).thenReturn(new KefuMsgTypeEntity());
        when(kefuMsgSubscribeDao.selectByEntity(any())).thenReturn(List.of(new KefuMsgSubscribeEntity()));

        Boolean result = kefuServiceImpl.sendMessage(KefuMsgTypeCode.VOTE, "clientId", "attr");
        Assertions.assertEquals(Boolean.TRUE, result);
    }

    @Test
    void testSendTemplate() throws Exception {
        when(mpUserService.findByJiacn(anyString())).thenReturn(null);

        KefuMsgTypeEntity kefuMsgTypeEntity = new KefuMsgTypeEntity();
        kefuMsgTypeEntity.setClientId("jia");
        KefuMsgSubscribeEntity kefuMsgSubscribeEntity = new KefuMsgSubscribeEntity();
        kefuMsgSubscribeEntity.setWxRxFlag(EsConstants.COMMON_YES);
        kefuMsgSubscribeEntity.setJiacn("cc");
        kefuServiceImpl.sendTemplate(kefuMsgTypeEntity, kefuMsgSubscribeEntity, "attr");
    }

    @Test
    void testSendWxTemplate() throws Exception {
        MpUserEntity mpUserEntity = new MpUserEntity();
        mpUserEntity.setAppid("appid");
        when(mpUserService.findByJiacn(anyString())).thenReturn(mpUserEntity);
        when(redisService.get(any())).thenReturn("");

        when(kefuMsgLogDao.insert(any())).thenReturn(1);
        WxMpService wxMpService = Mockito.mock(WxMpService.class);
        WxMpKefuService wxMpKefuService = Mockito.mock(WxMpKefuService.class);
        when(wxMpKefuService.sendKefuMessage(any())).thenReturn(true);
        when(wxMpService.getKefuService()).thenReturn(wxMpKefuService);
        when(mpInfoService.findWxMpService(anyString())).thenReturn(wxMpService);

        KefuMsgTypeEntity kefuMsgTypeEntity = new KefuMsgTypeEntity();
        kefuMsgTypeEntity.setClientId("clientId");
        kefuMsgTypeEntity.setWxTemplateTxt("templateId");
        KefuMsgSubscribeEntity kefuMsgSubscribeEntity = new KefuMsgSubscribeEntity();
        kefuMsgSubscribeEntity.setJiacn("jiacn");
        boolean result = kefuServiceImpl.sendWxTemplate(kefuMsgTypeEntity, kefuMsgSubscribeEntity.getJiacn(), "attr");
        Assertions.assertTrue(result);

        when(redisService.get(any())).thenReturn(null);
        MpTemplateEntity mpTemplateEntity = new MpTemplateEntity();
        mpTemplateEntity.setTemplateId("templateId");
        mpTemplateEntity.setAppid("appId");
        when(mpTemplateService.get(anyString())).thenReturn(mpTemplateEntity);
        WxMpTemplateMsgService wxMpTemplateMsgService = mock(WxMpTemplateMsgService.class);
        when(wxMpTemplateMsgService.sendTemplateMsg(any())).thenReturn("success");
        when(wxMpService.getTemplateMsgService()).thenReturn(wxMpTemplateMsgService);
        kefuMsgTypeEntity.setWxTemplateId("templateId");
        kefuMsgTypeEntity.setWxTemplate("[{\"name\":\"first\",\"value\":\"\",\"color\":\"#173177\"},{\"name\":\"keyword1\",\"value\":\"每天答题激活提醒\",\"color\":\"#173177\"},{\"name\":\"keyword2\",\"value\":\"请回复1重新激活，退订回复TD\",\"color\":\"#173177\"},{\"name\":\"remark\",\"value\":\"\",\"color\":\"#173177\"}]");
        result = kefuServiceImpl.sendWxTemplate(kefuMsgTypeEntity, kefuMsgSubscribeEntity.getJiacn(), "attr");
        Assertions.assertTrue(result);
    }
}
