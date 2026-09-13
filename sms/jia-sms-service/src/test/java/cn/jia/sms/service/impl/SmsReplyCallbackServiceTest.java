package cn.jia.sms.service.impl;

import cn.jia.core.lock.IDistributedLock;
import cn.jia.core.lock.ILock;
import cn.jia.sms.config.SmsExternalHttpClient;
import cn.jia.sms.entity.SmsConfigEntity;
import cn.jia.sms.entity.SmsReplyEntity;
import cn.jia.sms.entity.SmsSendEntity;
import cn.jia.sms.service.SmsService;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SmsReplyCallbackServiceTest extends BaseMockTest {
    @Mock SmsService smsService;
    @Mock SmsExternalHttpClient externalHttpClient;
    @Mock IDistributedLock distributedLock;
    @Mock ILock callbackLock;

    private SmsReplyCallbackService callbackService;

    @BeforeEach
    void setUp() throws Exception {
        callbackService = new SmsReplyCallbackService(smsService, externalHttpClient, Optional.of(distributedLock));
        lenient().when(distributedLock.tryLock(anyString(), eq(1_000L), eq(5_000L), eq(TimeUnit.MILLISECONDS),
                eq(false))).thenReturn(callbackLock);
    }

    @Test
    void duplicateCallbackReturnsWithoutAnotherNetworkCallOrWrite() {
        SmsSendEntity send = new SmsSendEntity().setMsgid("provider-1").setClientId("client-a");
        send.setTenantId("tenant-a");
        when(smsService.selectSend("provider-1")).thenReturn(send);
        when(smsService.findReply(any())).thenReturn(new SmsReplyEntity().setId(7L));

        callbackService.receive("13800000000", "YES", "provider-1", null);

        verifyNoInteractions(externalHttpClient);
        ArgumentCaptor<SmsReplyEntity> dedupe = ArgumentCaptor.forClass(SmsReplyEntity.class);
        verify(smsService).findReply(dedupe.capture());
        org.junit.jupiter.api.Assertions.assertEquals("client-a", dedupe.getValue().getClientId());
        org.junit.jupiter.api.Assertions.assertEquals("tenant-a", dedupe.getValue().getTenantId());
        verify(smsService, never()).reply(any());
        verify(smsService, never()).selectConfig(anyString());
    }

    @Test
    void invalidCallbackFailsBeforeLockOrDatabaseWork() {
        assertThrows(SmsReplyCallbackService.SmsReplyCallbackException.class,
                () -> callbackService.receive(" ", "YES", "provider-1", null));

        verifyNoInteractions(distributedLock, smsService, externalHttpClient);
    }

    @Test
    void successfulCallbackDeliversBeforePersistingAndScopesTheReply() {
        when(smsService.findReply(any())).thenReturn(null);
        SmsSendEntity send = new SmsSendEntity().setMsgid("provider-1").setClientId("client-a");
        send.setTenantId("tenant-a");
        when(smsService.selectSend("provider-1")).thenReturn(send);
        when(smsService.selectConfig("client-a")).thenReturn(new SmsConfigEntity()
                .setClientId("client-a").setReplyUrl("https://client.example/sms/reply"));
        when(externalHttpClient.beginOperation()).thenReturn(null);
        when(externalHttpClient.getForObject(isNull(), anyString(), eq(String.class))).thenReturn("ok");

        callbackService.receive("13800000000", "YES", "provider-1", "01");

        InOrder order = inOrder(externalHttpClient, smsService);
        order.verify(externalHttpClient).getForObject(isNull(), anyString(), eq(String.class));
        ArgumentCaptor<SmsReplyEntity> reply = ArgumentCaptor.forClass(SmsReplyEntity.class);
        order.verify(smsService).reply(reply.capture());
        org.junit.jupiter.api.Assertions.assertEquals("client-a", reply.getValue().getClientId());
        org.junit.jupiter.api.Assertions.assertEquals("tenant-a", reply.getValue().getTenantId());
    }

    @Test
    void failedDownstreamDeliveryDoesNotRecordCompletionSoProviderCanRetry() {
        when(smsService.findReply(any())).thenReturn(null);
        when(smsService.selectSend("provider-1")).thenReturn(new SmsSendEntity()
                .setMsgid("provider-1").setClientId("client-a"));
        when(smsService.selectConfig("client-a")).thenReturn(new SmsConfigEntity()
                .setClientId("client-a").setReplyUrl("https://client.example/sms/reply"));
        when(externalHttpClient.beginOperation()).thenReturn(null);
        when(externalHttpClient.getForObject(isNull(), anyString(), eq(String.class)))
                .thenThrow(new SmsExternalHttpClient.SmsExternalCallRejectedException("safe"));

        assertThrows(SmsExternalHttpClient.SmsExternalCallRejectedException.class,
                () -> callbackService.receive("13800000000", "YES", "provider-1", null));

        verify(smsService, never()).reply(any());
    }

    @Test
    void lockKeyIsOpaqueAndDoesNotContainCallbackPayload() throws Exception {
        when(smsService.selectSend("provider-1")).thenReturn(new SmsSendEntity()
                .setMsgid("provider-1").setClientId("client-a"));
        when(smsService.findReply(any())).thenReturn(new SmsReplyEntity().setId(7L));

        callbackService.receive("13800000000", "sensitive-content", "provider-1", "01");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(distributedLock).tryLock(key.capture(), eq(1_000L), eq(5_000L), eq(TimeUnit.MILLISECONDS),
                eq(false));
        assertFalse(key.getValue().contains("13800000000"));
        assertFalse(key.getValue().contains("sensitive-content"));
        assertFalse(key.getValue().contains("provider-1"));
    }
}
