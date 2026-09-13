package cn.jia.core.ldap;

import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LdapFailureClassifierTest {
    @Test
    void classifiesSocketTimeoutWithoutProviderMessageInspection() {
        assertEquals(LdapFailureClassifier.Failure.TIMEOUT,
                LdapFailureClassifier.classify(new RuntimeException(new SocketTimeoutException())));
    }

    @Test
    void leavesUnrelatedFailuresUntouched() {
        assertEquals(LdapFailureClassifier.Failure.OTHER,
                LdapFailureClassifier.classify(new IllegalArgumentException("invalid input")));
    }
}
