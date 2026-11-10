package cn.jia.kefu.service.impl;

import cn.jia.core.redis.RedisService;
import cn.jia.kefu.dao.KefuMsgLogDao;
import cn.jia.kefu.dao.KefuMsgTypeDao;
import cn.jia.kefu.entity.KefuMsgLogEntity;
import cn.jia.kefu.entity.KefuMsgTypeEntity;
import cn.jia.wx.entity.MpTemplateEntity;
import cn.jia.wx.entity.MpUserEntity;
import cn.jia.wx.service.MpInfoService;
import cn.jia.wx.service.MpTemplateService;
import cn.jia.wx.service.MpUserService;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpKefuService;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.api.WxMpTemplateMsgService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** No actual SDK transport; preserves recipient, template and send-outcome boundaries. */
class KefuScheduledNoticeTest {
    KefuServiceImpl service; KefuMsgTypeDao types; KefuMsgLogDao logs;
    MpUserService users; MpInfoService info; MpTemplateService templates; RedisService redis;
    WxMpService sdk; WxMpKefuService text; WxMpTemplateMsgService template;
    KefuMsgTypeEntity type; MpUserEntity user;
    @BeforeEach void setup() {
        service=new KefuServiceImpl(); types=mock(KefuMsgTypeDao.class);logs=mock(KefuMsgLogDao.class);
        users=mock(MpUserService.class);info=mock(MpInfoService.class);templates=mock(MpTemplateService.class);redis=mock(RedisService.class);
        ReflectionTestUtils.setField(service,"kefuMsgTypeDao",types);ReflectionTestUtils.setField(service,"kefuMsgLogDao",logs);
        ReflectionTestUtils.setField(service,"mpUserService",users);ReflectionTestUtils.setField(service,"mpInfoService",info);
        ReflectionTestUtils.setField(service,"mpTemplateService",templates);ReflectionTestUtils.setField(service,"redisService",redis);
        sdk=mock(WxMpService.class);text=mock(WxMpKefuService.class);template=mock(WxMpTemplateMsgService.class);
        when(info.findWxMpService("app")).thenReturn(sdk);when(sdk.getKefuService()).thenReturn(text);
        when(sdk.getTemplateMsgService()).thenReturn(template);
        type=new KefuMsgTypeEntity().setWxTemplateTxt("Hello #0#").setWxTemplateId("template").setWxTemplate("[]");
        type.setTenantId("tenant");type.setClientId("client");
        user=new MpUserEntity().setAppid("app").setOpenId("open").setJiacn("owner").setSubscribe(1);
        user.setTenantId("tenant");user.setClientId("client");
        when(types.findScheduledMsgType("client","vote")).thenReturn(type);
        when(users.findScheduledRecipient("client","owner")).thenReturn(user);
    }
    MpTemplateEntity matchingTemplate() {
        var result=new MpTemplateEntity().setTemplateId("template").setAppid("app");
        result.setTenantId("tenant");result.setClientId("client");return result;
    }
    @Test void exactRecipientAndActivityUsedOnceAndSuccessfulLogKeepsScope() throws Exception {
        when(redis.get("active_mp_user_open")).thenReturn("1");when(text.sendKefuMessage(any())).thenReturn(true);
        var result=service.sendScheduledWxTemplate("client","vote","owner","world");
        assertTrue(result.sent());assertTrue(result.active());
        verify(users).findScheduledRecipient("client","owner");verify(users,never()).findByJiacn(anyString());
        verify(redis).get("active_mp_user_open");verifyNoInteractions(templates);
        var captured=ArgumentCaptor.forClass(KefuMsgLogEntity.class);verify(logs).insert(captured.capture());
        assertEquals("tenant",captured.getValue().getTenantId());assertEquals("client",captured.getValue().getClientId());
    }
    @Test void missingScopedDefinitionOrRecipientNeverCallsProvider() throws Exception {
        when(types.findScheduledMsgType("client","vote")).thenReturn(null);
        assertFalse(service.sendScheduledWxTemplate("client","vote","owner").sent());verifyNoInteractions(users,info);
        when(types.findScheduledMsgType("client","vote")).thenReturn(type);
        when(users.findScheduledRecipient("client","owner")).thenReturn(null);
        assertFalse(service.sendScheduledWxTemplate("client","vote","owner").sent());verifyNoInteractions(info);
    }
    @Test void foreignTemplateCannotSendAcrossAppOrTenantOrClient() {
        for (int field=0;field<3;field++) {
            var wrong=matchingTemplate();
            if(field==0) wrong.setAppid("App");if(field==1) wrong.setTenantId("Tenant");if(field==2) wrong.setClientId("Client");
            when(templates.get("template")).thenReturn(wrong);
            assertThrows(IllegalStateException.class,()->service.sendScheduledWxTemplate("client","vote","owner"));
        }
        verifyNoInteractions(info,logs);
    }
    @Test void unknownTextOutcomeDoesNotFallBackToAnotherSend() throws Exception {
        when(redis.get("active_mp_user_open")).thenReturn("1");
        var failure=mock(WxErrorException.class);when(failure.getMessage()).thenReturn("connection lost after write");
        when(text.sendKefuMessage(any())).thenThrow(failure);
        assertSame(failure,assertThrows(WxErrorException.class,()->service.sendScheduledWxTemplate("client","vote","owner")));
        verifyNoInteractions(templates,template,logs);
    }
    @Test void explicitTextNonDeliveryStillAllowsTemplateFallback() throws Exception {
        when(redis.get("active_mp_user_open")).thenReturn("1");when(text.sendKefuMessage(any())).thenReturn(false);
        when(templates.get("template")).thenReturn(matchingTemplate());when(template.sendTemplateMsg(any())).thenReturn("message-id");
        var result=service.sendScheduledWxTemplate("client","vote","owner");assertTrue(result.sent());assertTrue(result.active());
        verify(text).sendKefuMessage(any());verify(template).sendTemplateMsg(any());
    }
}
