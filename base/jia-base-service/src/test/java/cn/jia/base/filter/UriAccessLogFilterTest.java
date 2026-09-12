package cn.jia.base.filter;

import cn.jia.base.dao.LogDao;
import cn.jia.base.entity.LogEntity;
import cn.jia.base.service.impl.LogServiceImpl;
import cn.jia.core.config.SpringContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.web.MockFilterConfig;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UriAccessLogFilterTest {
    private LogDao logDao;
    private UriAccessLogFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        logDao = mock(LogDao.class);
        LogServiceImpl logService = new LogServiceImpl();
        ReflectionTestUtils.setField(logService, "baseDao", logDao);
        when(logDao.insert(any(LogEntity.class))).thenReturn(1);
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("logService", logService);
        SpringContextHolder holder = new SpringContextHolder();
        holder.setApplicationContext(context);
        filter = new UriAccessLogFilter();
        filter.init(new MockFilterConfig());
    }

    @AfterEach
    void tearDown() {
        SpringContextHolder.cleanApplicationContext();
    }

    @Test
    void streamsBinaryOutputUploadWithoutConsumingOrTranscodingItAndKeepsAuditMetadata()
            throws Exception {
        byte[] png = new byte[] {
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                0x00, (byte) 0xff, (byte) 0xc3, 0x28, (byte) 0x80
        };
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT", "/agent/output-uploads/upload-1/content") {
            @Override
            public Map<String, String[]> getParameterMap() {
                throw new AssertionError("streaming upload audit must not parse servlet parameters");
            }
        };
        request.setContentType("application/octet-stream");
        request.setContent(png);
        request.setQueryString("trace=upload-1&token=must-not-persist");

        filter.doFilter(request, new MockHttpServletResponse(), (downstream, response) ->
                assertArrayEquals(png, downstream.getInputStream().readAllBytes()));

        ArgumentCaptor<LogEntity> logged = ArgumentCaptor.forClass(LogEntity.class);
        verify(logDao).insert(logged.capture());
        assertEquals("PUT", logged.getValue().getMethod());
        assertEquals("/agent/output-uploads/upload-1/content", logged.getValue().getUri());
        assertTrue(logged.getValue().getParam().contains("upload-1"));
        assertFalse(logged.getValue().getParam().contains("must-not-persist"));
    }

    @Test
    void cachedJsonRequestReplaysTheOriginalUtf8Bytes() throws Exception {
        byte[] json = "{\"title\":\"悬赏交付\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agent/tasks");
        request.setContentType("application/json");
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        request.setContent(json);

        filter.doFilter(request, new MockHttpServletResponse(), (downstream, response) ->
                assertArrayEquals(json, downstream.getInputStream().readAllBytes()));

        ArgumentCaptor<LogEntity> logged = ArgumentCaptor.forClass(LogEntity.class);
        verify(logDao).insert(logged.capture());
        assertTrue(logged.getValue().getParam().contains("悬赏交付"));
    }

    @Test
    void streamingUploadWithoutQueryNeverFallsBackToServletParameterParsing()
            throws Exception {
        byte[] binary = new byte[] {(byte) 0xff, 0x00, (byte) 0x89, 0x50, 0x4b};
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT", "/agent/output-uploads/upload-2/content") {
            @Override
            public Map<String, String[]> getParameterMap() {
                throw new AssertionError("metadata-only audit must not parse the upload body");
            }
        };
        request.setContentType("application/x-www-form-urlencoded");
        request.setContent(binary);

        filter.doFilter(request, new MockHttpServletResponse(), (downstream, response) ->
                assertArrayEquals(binary, downstream.getInputStream().readAllBytes()));

        ArgumentCaptor<LogEntity> logged = ArgumentCaptor.forClass(LogEntity.class);
        verify(logDao).insert(logged.capture());
        assertNull(logged.getValue().getParam());
    }

    @Test
    void streamingRouteMatchIsExactAndContextAware() {
        MockHttpServletRequest exact = new MockHttpServletRequest(
                "PUT", "/api/agent/output-uploads/upload-1/content");
        exact.setContextPath("/api");
        assertTrue(UriAccessLogFilter.isStreamingOutputUpload(exact));

        MockHttpServletRequest wrongMethod = new MockHttpServletRequest(
                "POST", "/api/agent/output-uploads/upload-1/content");
        wrongMethod.setContextPath("/api");
        assertFalse(UriAccessLogFilter.isStreamingOutputUpload(wrongMethod));

        assertFalse(UriAccessLogFilter.isStreamingOutputUpload(new MockHttpServletRequest(
                "PUT", "/agent/output-uploads/upload-1/content/extra")));
        assertFalse(UriAccessLogFilter.isStreamingOutputUpload(new MockHttpServletRequest(
                "PUT", "/agent/output-uploads/upload-1")));
    }
}
