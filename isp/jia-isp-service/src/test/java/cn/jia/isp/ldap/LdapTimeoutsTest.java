package cn.jia.isp.ldap;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LdapTimeoutsTest {
    @Test
    void defaultsFitTheLdapBudget() {
        assertDoesNotThrow(new LdapTimeouts()::validate);
    }

    @Test
    void rejectsPhaseBudgetsBeyondTheTotal() {
        LdapTimeouts timeouts = new LdapTimeouts();
        ReflectionTestUtils.setField(timeouts, "readTimeoutMillis", 2001);

        assertThrows(IllegalStateException.class, timeouts::validate);
    }
}
