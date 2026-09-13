package cn.jia.isp.ldap;

import cn.jia.core.deadline.RequestDeadline;
import cn.jia.core.deadline.RequestDeadlineContext;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.test.BaseMockTest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.naming.CommunicationException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LdapRequestBudgetAspectTest extends BaseMockTest {

    private final LdapRequestBudgetAspect aspect = new LdapRequestBudgetAspect();

    @AfterEach
    void clearsDeadlineContext() {
        assertEquals(false, RequestDeadlineContext.current().isPresent());
    }

    @Test
    void expiredPerformanceDeadlineDoesNotRefuseLdapWork() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        Object providerResult = new Object();
        when(joinPoint.proceed()).thenReturn(providerResult);

        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(RequestDeadline.start(0))) {
            assertSame(providerResult, aspect.guardAndClassifyLdapServiceCall(joinPoint));
        }

        verify(joinPoint).proceed();
    }

    @Test
    void actualProviderTimeoutStillReturnsTheExistingSafeLdapFailure() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenThrow(new RuntimeException(new SocketTimeoutException("synthetic")));

        EsRuntimeException failure = assertThrows(EsRuntimeException.class,
                () -> aspect.guardAndClassifyLdapServiceCall(joinPoint));

        assertEquals("EISP002", failure.getMessageKey());
    }

    @Test
    void actualProviderConnectionFailureStillReturnsTheExistingUnavailableFailure() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenThrow(new RuntimeException(new CommunicationException("synthetic")));

        EsRuntimeException failure = assertThrows(EsRuntimeException.class,
                () -> aspect.guardAndClassifyLdapServiceCall(joinPoint));

        assertEquals("EISP003", failure.getMessageKey());
    }

    @Test
    void existingBusinessFailureIsNotReclassified() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        EsRuntimeException businessFailure = new EsRuntimeException("EISP001", "existing");
        when(joinPoint.proceed()).thenThrow(businessFailure);

        assertSame(businessFailure, assertThrows(EsRuntimeException.class,
                () -> aspect.guardAndClassifyLdapServiceCall(joinPoint)));
    }
}
