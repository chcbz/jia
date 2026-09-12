package cn.jia.base.filter;

import cn.jia.base.entity.LogEntity;
import cn.jia.base.service.LogService;
import cn.jia.base.service.impl.LogServiceImpl;
import cn.jia.core.context.EsContextHolder;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.web.filter.CharacterEncodingFilter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

import static org.junit.jupiter.api.Assertions.*;
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

    @Test
    void realTomcatPreservesLoginTokenFormsMultipartAndAuditRedaction(@TempDir Path temp) throws Exception {
        // MockHttpServletRequest does not parse form bodies. Real HTTP catches the original bug.
        var audits = new CopyOnWriteArrayList<LogEntity>();
        var pendingWrites = new CopyOnWriteArrayList<Runnable>();
        LogService logService = mock(LogService.class);
        LogServiceImpl sanitizer = new LogServiceImpl();
        when(logService.captureLog(any(EsRequestWrapper.class))).thenAnswer(invocation -> {
            LogEntity record = sanitizer.captureLog(invocation.getArgument(0));
            audits.add(record);
            return record;
        });
        AuditDispatcher dispatcher = new AuditDispatcher() {
            @Override public void dispatch(Runnable write) { pendingWrites.add(write); }
            @Override public void notifyAfterCommit(Runnable write) { throw new AssertionError(); }
        };
        var factory = new TomcatServletWebServerFactory(0);
        factory.setBaseDirectory(temp.resolve("tomcat").toFile());
        var server = factory.getWebServer(context -> {
            context.addFilter("encoding", new CharacterEncodingFilter("UTF-8", true))
                    .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
            context.addFilter("audit", new UriAccessLogFilter(logService, dispatcher))
                    .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");
            var servlet = context.addServlet("fixture", new HttpServlet() {
                @Override protected void doPost(HttpServletRequest req, HttpServletResponse res)
                        throws IOException, ServletException {
                    try {
                        res.setContentType("text/plain;charset=UTF-8");
                        switch (req.getRequestURI()) {
                            case "/login" -> res.getWriter().print(req.getParameter("username") + "|"
                                    + req.getParameter("password"));
                            case "/oauth2/token" -> {
                                for (String name : List.of("grant_type", "client_id", "code", "code_verifier", "redirect_uri")) {
                                    res.getWriter().println(req.getParameter(name));
                                }
                            }
                            case "/profile/update" -> res.getWriter().print(req.getParameter("display_name") + "|"
                                    + String.join(",", req.getParameterValues("tag")));
                            case "/upload" -> res.getWriter().print(req.getParameter("title") + "|"
                                    + new String(req.getPart("file").getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                            case "/json" -> {
                                byte[] first = req.getInputStream().readAllBytes();
                                byte[] second = req.getInputStream().readAllBytes();
                                if (!java.util.Arrays.equals(first, second)) { res.sendError(500); return; }
                                res.getOutputStream().write(second);
                            }
                            default -> res.sendError(404);
                        }
                    } finally {
                        EsContextHolder.clearContext();
                    }
                }
            });
            servlet.addMapping("/*");
            servlet.setMultipartConfig(new MultipartConfigElement(temp.toString(), 1024 * 1024, 2 * 1024 * 1024, 0));
            servlet.setLoadOnStartup(1);
        });
        try {
            server.start();
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String base = "http://127.0.0.1:" + server.getPort();
            assertEquals("宋江|fixture+pass&word", post(client, base + "/login",
                    "application/x-www-form-urlencoded;charset=UTF-8",
                    "username=%E5%AE%8B%E6%B1%9F&password=fixture%2Bpass%26word"));
            assertEquals("authorization_code\nfixture-client\nfixture-code\nfixture-verifier\nhttps://client.example/callback\n",
                    post(client, base + "/oauth2/token", "application/x-www-form-urlencoded",
                            "grant_type=authorization_code&client_id=fixture-client&code=fixture-code"
                                    + "&code_verifier=fixture-verifier&redirect_uri=https%3A%2F%2Fclient.example%2Fcallback"));
            String profile = "display_name=%E5%AE%8B%E6%B1%9F&tag=body-one&tag=body-two&password=hidden-profile-secret";
            assertEquals("宋江|query,body-one,body-two", post(client, base + "/profile/update?tag=query",
                    "application/x-www-form-urlencoded;charset=UTF-8", profile));
            assertEquals("宋江|body-one,body-two", post(client, base + "/profile/update",
                    "application/x-www-form-urlencoded;charset=UTF-8", profile));
            String multipart = "--fixture-boundary\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\nfixture-title\r\n"
                    + "--fixture-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"fixture.txt\"\r\n"
                    + "Content-Type: text/plain\r\n\r\nfixture-file-content\r\n--fixture-boundary--\r\n";
            assertEquals("fixture-title|fixture-file-content", post(client, base + "/upload",
                    "multipart/form-data; boundary=fixture-boundary", multipart));
            String json = "{\"safe\":\"宋江\",\"password\":\"hidden-json-secret\"}";
            assertEquals(json, post(client, base + "/json", "application/json;charset=UTF-8", json));
            assertEquals(6, audits.size());
            assertEquals(6, pendingWrites.size());
            assertNull(audits.get(0).getParam());
            assertNull(audits.get(1).getParam());
            assertTrue(audits.get(3).getParam().contains("宋江"));
            for (LogEntity record : audits) {
                String captured = record.getParam() + "|" + record.getHeader();
                for (String secret : List.of("fixture+pass", "fixture-code", "fixture-verifier",
                        "hidden-profile-secret", "hidden-json-secret", "fixture-file-content")) {
                    assertFalse(captured.contains(secret), "credential/file content must not enter audit");
                }
            }
            verify(logService, never()).persistLog(any());
        } finally {
            server.stop();
        }
    }

    private static String post(HttpClient client, String url, String contentType, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
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
