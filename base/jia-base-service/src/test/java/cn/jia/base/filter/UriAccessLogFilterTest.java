package cn.jia.base.filter;

import cn.jia.base.entity.LogEntity;
import cn.jia.base.service.LogService;
import cn.jia.core.audit.AuditAdmissionException;
import cn.jia.core.audit.AuditDispatcher;
import cn.jia.core.common.EsRequestWrapper;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UriAccessLogFilterTest {
    @Test
    void queuesDetachedSanitizedRecordWithoutPersistingOnRequestThread() throws Exception {
        LogService logService = mock(LogService.class);
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        UriAccessLogFilter filter = new UriAccessLogFilter(logService, dispatcher);
        LogEntity sanitizedAudit = new LogEntity().setUri("/tasks").setParam("{\"safe\":\"kept\"}");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/tasks");
        request.setContentType("application/json");
        request.setContent("{\"password\":\"raw-secret\"}".getBytes(StandardCharsets.UTF_8));
        when(logService.captureLog(any(EsRequestWrapper.class))).thenReturn(sanitizedAudit);
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, new MockHttpServletResponse(), (wrappedRequest, response) -> {
            assertInstanceOf(EsRequestWrapper.class, wrappedRequest);
            chainCalled.set(true);
        });

        assertTrue(chainCalled.get());
        verify(logService).captureLog(any(EsRequestWrapper.class));
        verify(logService, never()).persistLog(any());
        dispatcher.queued.get().run();
        verify(logService).persistLog(sanitizedAudit);
    }

    @Test
    void admissionFailureStopsDownstreamBeforeBusinessSideEffects() throws Exception {
        LogService logService = mock(LogService.class);
        AuditDispatcher rejecting = new AuditDispatcher() {
            @Override
            public void dispatch(Runnable auditWrite) {
                throw new AuditAdmissionException("full");
            }

            @Override
            public void notifyAfterCommit(Runnable durableOutboxWakeUp) {
            }
        };
        UriAccessLogFilter filter = new UriAccessLogFilter(logService, rejecting);
        LogEntity sanitizedAudit = new LogEntity().setUri("/write");
        when(logService.captureLog(any(EsRequestWrapper.class))).thenReturn(sanitizedAudit);
        AtomicBoolean chainCalled = new AtomicBoolean();

        assertThrows(AuditAdmissionException.class, () -> filter.doFilter(
                new MockHttpServletRequest("POST", "/write"), new MockHttpServletResponse(),
                (request, response) -> chainCalled.set(true)));

        verify(logService, never()).persistLog(any());
        assertFalse(chainCalled.get());
    }

    @Test
    void excludedStaticResourceBypassesCaptureAndDispatch() throws Exception {
        LogService logService = mock(LogService.class);
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        UriAccessLogFilter filter = new UriAccessLogFilter(logService, dispatcher);
        filter.init(filterConfig("*.js,/druid/*"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/assets/app.js");
        AtomicReference<Object> chainedRequest = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (actualRequest, response) ->
                chainedRequest.set(actualRequest));

        assertSame(request, chainedRequest.get());
        verify(logService, never()).captureLog(any());
        verify(logService, never()).persistLog(any());
        assertNull(dispatcher.queued.get());
    }

    private FilterConfig filterConfig(String exclusions) {
        return new FilterConfig() {
            @Override
            public String getFilterName() {
                return "uriAccessLogFilter";
            }

            @Override
            public jakarta.servlet.ServletContext getServletContext() {
                return null;
            }

            @Override
            public String getInitParameter(String name) {
                return "exclusions".equals(name) ? exclusions : null;
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return Collections.enumeration(Collections.singleton("exclusions"));
            }
        };
    }

    private static final class RecordingDispatcher implements AuditDispatcher {
        private final AtomicReference<Runnable> queued = new AtomicReference<>();

        @Override
        public void dispatch(Runnable auditWrite) {
            if (!queued.compareAndSet(null, auditWrite)) {
                throw new AssertionError("unexpected second audit write");
            }
        }

        @Override
        public void notifyAfterCommit(Runnable durableOutboxWakeUp) {
            throw new AssertionError("access filter must not couple attempts to business commit");
        }
    }
}
