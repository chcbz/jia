package cn.jia.sms.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SmsExternalHttpTimeoutsTest {
    @Test
    void defaultsFitTheTotalBudget() {
        SmsExternalHttpTimeouts timeouts = new SmsExternalHttpTimeouts();

        assertDoesNotThrow(timeouts::validate);
        assertEquals(250, timeouts.getConnectionRequestTimeoutMillis());
        assertEquals(500, timeouts.getConnectTimeoutMillis());
        assertEquals(1750, timeouts.getReadTimeoutMillis());
        assertEquals(2500, timeouts.getTotalTimeoutMillis());
    }

    @Test
    void rejectsPhaseBudgetsThatExceedTheTotalBudget() {
        SmsExternalHttpTimeouts timeouts = new SmsExternalHttpTimeouts();
        ReflectionTestUtils.setField(timeouts, "readTimeoutMillis", 1751);

        assertThrows(IllegalStateException.class, timeouts::validate);
    }
}
