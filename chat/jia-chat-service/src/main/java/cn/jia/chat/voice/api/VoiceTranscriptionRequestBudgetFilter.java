package cn.jia.chat.voice.api;

import cn.jia.core.entity.JsonResult;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Enforces the transcription envelope budget before multipart controller dispatch. */
@Component
@Order(-90)
public final class VoiceTranscriptionRequestBudgetFilter extends OncePerRequestFilter {
    public static final long MAX_REQUEST_BYTES = 6L * 1024 * 1024;
    private static final String PATH = "/chat/speech/transcriptions";

    private final ObjectMapper objectMapper;

    public VoiceTranscriptionRequestBudgetFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !PATH.equals(pathWithinApplication(request));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > MAX_REQUEST_BYTES) {
            reject(response);
            return;
        }
        HttpServletRequest bounded = new BudgetRequestWrapper(request, MAX_REQUEST_BYTES);
        try {
            filterChain.doFilter(bounded, response);
        } catch (IOException | ServletException | RuntimeException exception) {
            if (!hasBudgetCause(exception) || response.isCommitted()) {
                throw exception;
            }
            reject(response);
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        VoiceErrorCode error = VoiceErrorCode.TOO_LARGE;
        JsonResult<VoiceErrorData> result = new JsonResult<>(
                new VoiceErrorData(null), error.message(), error.code(), error.status().value());
        byte[] body = objectMapper.writeValueAsBytes(result);
        response.reset();
        response.setStatus(error.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            return path.substring(contextPath.length());
        }
        return path;
    }

    private static boolean hasBudgetCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof RequestBudgetExceededException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class BudgetRequestWrapper extends HttpServletRequestWrapper {
        private final long budget;
        private ServletInputStream inputStream;

        private BudgetRequestWrapper(HttpServletRequest request, long budget) {
            super(request);
            this.budget = budget;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (inputStream == null) {
                inputStream = new BudgetServletInputStream(super.getInputStream(), budget);
            }
            return inputStream;
        }
    }

    private static final class BudgetServletInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long budget;
        private long consumed;

        private BudgetServletInputStream(ServletInputStream delegate, long budget) {
            this.delegate = delegate;
            this.budget = budget;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                record(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = delegate.read(bytes, offset, length);
            if (read > 0) {
                record(read);
            }
            return read;
        }

        @Override
        public long skip(long count) throws IOException {
            if (count <= 0) {
                return 0;
            }
            byte[] buffer = new byte[(int) Math.min(8_192L, count)];
            long skipped = 0;
            while (skipped < count) {
                int read = read(buffer, 0, (int) Math.min(buffer.length, count - skipped));
                if (read < 0) {
                    break;
                }
                skipped += read;
            }
            return skipped;
        }

        private void record(long count) throws RequestBudgetExceededException {
            consumed = Math.addExact(consumed, count);
            if (consumed > budget) {
                throw new RequestBudgetExceededException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }

    private static final class RequestBudgetExceededException extends IOException {
        private RequestBudgetExceededException() {
            super("voice transcription request budget exceeded");
        }
    }
}
