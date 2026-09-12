package cn.jia.sms.api;

import cn.jia.core.entity.JsonResult;
import cn.jia.sms.service.impl.SmsReplyCallbackService;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;

class SmsControllerReplyCallbackTest extends BaseMockTest {
    @Mock SmsReplyCallbackService callbackService;

    private SmsController controller;

    @BeforeEach
    void setUp() {
        controller = new SmsController();
        ReflectionTestUtils.setField(controller, "smsReplyCallbackService", callbackService);
    }

    @Test
    void successKeepsTheExistingJsonResultContract() {
        JsonResult<?> result = (JsonResult<?>) controller.receive("13800000000", "YES", "provider-1", null);

        assertEquals("E0", result.getCode());
        assertEquals("ok", result.getMsg());
    }

    @Test
    void dependencyFailureReturnsAFixedFailureWithoutProviderDetails() {
        doThrow(new SmsReplyCallbackService.SmsReplyCallbackException("provider-sensitive-detail"))
                .when(callbackService).receive("13800000000", "YES", "provider-1", null);

        JsonResult<?> result = (JsonResult<?>) controller.receive("13800000000", "YES", "provider-1", null);

        assertEquals("E999", result.getCode());
        assertEquals("短信回复处理失败", result.getMsg());
    }
}
