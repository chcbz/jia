package cn.jia.sms.service.impl;

import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.lock.IDistributedLock;
import cn.jia.sms.dao.*;
import cn.jia.sms.entity.*;
import cn.jia.test.BaseMockTest;
import com.github.pagehelper.PageInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class SmsServiceImplTest extends BaseMockTest {
    @Mock
    SmsCodeDao smsCodeDao;
    @Mock
    SmsSendDao smsSendDao;
    @Mock
    SmsReplyDao smsReplyDao;
    @Mock
    SmsConfigDao smsConfigDao;
    @Mock
    SmsBuyDao smsBuyDao;
    @Mock
    SmsTemplateDao smsTemplateDao;
    @Mock
    IDistributedLock distributedLock;
    @InjectMocks
    SmsServiceImpl smsServiceImpl;

    @Test
    void testCreate() {
        when(smsCodeDao.insert(any())).thenReturn(1);

        SmsCodeEntity smsCodeEntity = new SmsCodeEntity();
        SmsCodeEntity result = smsServiceImpl.create(smsCodeEntity);
        Assertions.assertEquals(smsCodeEntity, result);
    }

    @Test
    void testUpdate() {
        when(smsCodeDao.updateById(any())).thenReturn(1);

        SmsCodeEntity smsCodeEntity = new SmsCodeEntity();
        SmsCodeEntity result = smsServiceImpl.update(smsCodeEntity);
        Assertions.assertEquals(smsCodeEntity, result);
    }

    @Test
    void testSelectSmsCodeNoUsed() {
        SmsCodeEntity smsCodeEntity = new SmsCodeEntity();
        when(smsCodeDao.selectByEntity(any())).thenReturn(List.of(smsCodeEntity));

        SmsCodeEntity result = smsServiceImpl.selectSmsCodeNoUsed("phone", 0);
        Assertions.assertEquals(smsCodeEntity, result);
    }

    @Test
    void testUseSmsCode() {
        when(smsCodeDao.updateById(any())).thenReturn(1);

        smsServiceImpl.useSmsCode(1L);
    }

    @Test
    void testUpsert() {
        when(smsCodeDao.selectByEntity(any())).thenReturn(List.of(new SmsCodeEntity()));
        when(smsCodeDao.updateById(any())).thenReturn(1);
        when(distributedLock.lock(any())).thenReturn(null);

        String result = smsServiceImpl.upsert("phone", 0);
        assertNotNull(result);

        when(smsCodeDao.selectByEntity(any())).thenReturn(new ArrayList<>());
        result = smsServiceImpl.upsert("phone", 0);
        assertNotNull(result);

        when(distributedLock.lock(any())).thenThrow(IllegalStateException.class);
        assertThrows(IllegalStateException.class, () -> smsServiceImpl.upsert("phone", 0));
    }

    @Test
    void testFind() {
        SmsCodeEntity smsCodeEntity = new SmsCodeEntity();
        when(smsCodeDao.selectById(any())).thenReturn(smsCodeEntity);

        SmsCodeEntity result = smsServiceImpl.find(1L);
        Assertions.assertEquals(smsCodeEntity, result);
    }

    @Test
    void testDelete() {
        when(smsCodeDao.deleteById(any())).thenReturn(1);

        smsServiceImpl.delete(1L);
    }

    @Test
    void testSend() {
        when(smsSendDao.insert(any())).thenReturn(0);
        when(smsConfigDao.reduce(any())).thenReturn(0);
        SmsSendEntity smsSendEntity = new SmsSendEntity();
        smsSendEntity.setClientId("clientId");
        smsServiceImpl.send(smsSendEntity);
    }

    @Test
    void testSelectSend() {
        SmsSendEntity smsSendEntity = new SmsSendEntity();
        when(smsSendDao.selectById(any())).thenReturn(smsSendEntity);

        SmsSendEntity result = smsServiceImpl.selectSend("msgid");
        Assertions.assertEquals(smsSendEntity, result);
    }

    @Test
    void testListSend() {
        SmsSendEntity smsSendEntity = new SmsSendEntity();
        when(smsSendDao.selectByEntity(any())).thenReturn(List.of(smsSendEntity));

        PageInfo<SmsSendEntity> result = smsServiceImpl.listSend(new SmsSendVO(), 1, 1, "update_time desc");
        Assertions.assertEquals(1, result.getSize());
        Assertions.assertEquals(smsSendEntity, result.getList().get(0));
    }

    @Test
    void testReply() {
        when(smsReplyDao.insert(any())).thenReturn(1);

        smsServiceImpl.reply(new SmsReplyEntity());
    }

    @Test
    void testListReply() {
        SmsReplyEntity smsReplyEntity = new SmsReplyEntity();
        when(smsReplyDao.selectByEntity(any())).thenReturn(List.of(smsReplyEntity));

        PageInfo<SmsReplyEntity> result = smsServiceImpl.listReply(smsReplyEntity, 1, 1, "update_time desc");
        Assertions.assertEquals(1, result.getSize());
        Assertions.assertEquals(smsReplyEntity, result.getList().get(0));
    }

    @Test
    void testSelectConfig() {
        SmsConfigEntity smsConfigEntity = new SmsConfigEntity();
        when(smsConfigDao.selectById(any())).thenReturn(smsConfigEntity);

        SmsConfigEntity result = smsServiceImpl.selectConfig("clientId");
        Assertions.assertEquals(smsConfigEntity, result);
    }

    @Test
    void testCreateConfig() {
        when(smsConfigDao.insert(any())).thenReturn(1);

        smsServiceImpl.createConfig(new SmsConfigEntity());
    }

    @Test
    void testUpdateConfig() {
        when(smsConfigDao.updateById(any())).thenReturn(1);

        smsServiceImpl.updateConfig(new SmsConfigEntity());
    }

    @Test
    void testBuy() {
        try (MockedStatic<EsContextHolder> mockedStatic = mockStatic(EsContextHolder.class)) {
            EsContext esContext = mock(EsContext.class);
            when(esContext.getClientId()).thenReturn("clientId");
            mockedStatic.when(EsContextHolder::getContext).thenReturn(esContext);

            when(distributedLock.lock(any())).thenReturn(null);

            when(smsConfigDao.updateById(any())).thenReturn(1);
            SmsConfigEntity smsConfigEntity = new SmsConfigEntity();
            smsConfigEntity.setRemain(1);
            when(smsConfigDao.selectById(any())).thenReturn(smsConfigEntity);
            SmsBuyEntity smsBuyEntity = new SmsBuyEntity();
            smsBuyEntity.setNumber(1);
            when(smsBuyDao.selectLatest(anyString())).thenReturn(smsBuyEntity);
            when(smsBuyDao.insert(any())).thenReturn(1);

            smsServiceImpl.buy(1, 0d);
        }
    }

    @Test
    void testCreateTemplate() {
        when(smsTemplateDao.insert(any())).thenReturn(1);

        SmsTemplateEntity smsTemplateEntity = new SmsTemplateEntity();
        SmsTemplateEntity result = smsServiceImpl.createTemplate(smsTemplateEntity);
        Assertions.assertEquals(smsTemplateEntity, result);
    }

    @Test
    void testFindTemplate() {
        SmsTemplateEntity smsTemplateEntity = new SmsTemplateEntity();
        when(smsTemplateDao.selectById(any())).thenReturn(smsTemplateEntity);

        SmsTemplateEntity result = smsServiceImpl.findTemplate("templateId");
        Assertions.assertEquals(smsTemplateEntity, result);
    }

    @Test
    void testUpdateTemplate() {
        when(smsTemplateDao.updateById(any())).thenReturn(1);

        SmsTemplateEntity smsTemplateEntity = new SmsTemplateEntity();
        SmsTemplateEntity result = smsServiceImpl.updateTemplate(smsTemplateEntity);
        Assertions.assertEquals(smsTemplateEntity, result);
    }

    @Test
    void testDeleteTemplate() {
        when(smsTemplateDao.updateById(any())).thenReturn(1);

        smsServiceImpl.deleteTemplate("templateId");
    }

    @Test
    void testListTemplate() {
        SmsTemplateEntity smsTemplateEntity = new SmsTemplateEntity();
        when(smsTemplateDao.selectByEntity(any())).thenReturn(List.of(smsTemplateEntity));

        PageInfo<SmsTemplateEntity> result = smsServiceImpl.listTemplate(new SmsTemplateVO(), 1, 1, null);
        Assertions.assertEquals(1, result.getSize());
        Assertions.assertEquals(smsTemplateEntity, result.getList().get(0));
    }
}
