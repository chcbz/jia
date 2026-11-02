package cn.jia.chat.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatTypedDeliberationControllerTest {
    @Test
    void diagnosisRetainsCauseLocationsButNotSensitiveExceptionMessages() {
        var cause = new IllegalArgumentException("private user input and SQL value");
        cause.setStackTrace(new StackTraceElement[]{new StackTraceElement("OwnerService", "admit", "OwnerService.java", 42)});
        var failure = new IllegalStateException("private token", cause);
        failure.setStackTrace(new StackTraceElement[0]);
        String sites = ChatTypedDeliberationController.failureSites(failure);
        assertTrue(sites.contains("java.lang.IllegalStateException"));
        assertTrue(sites.contains("java.lang.IllegalArgumentException"));
        assertTrue(sites.contains("OwnerService.admit(OwnerService.java:42)"));
        assertFalse(sites.contains("private"));
        assertFalse(sites.contains("SQL value"));
    }

    @Test
    void diagnosisTerminatesForCyclicCauses() {
        var first = new IllegalStateException("secret one");
        var second = new IllegalArgumentException("secret two", first);
        first.initCause(second);
        first.setStackTrace(new StackTraceElement[0]);
        second.setStackTrace(new StackTraceElement[0]);
        assertEquals("java.lang.IllegalStateException caused_by java.lang.IllegalArgumentException",
                ChatTypedDeliberationController.failureSites(first));
    }
}
