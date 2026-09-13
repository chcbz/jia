package cn.jia.core.common;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * @author chc
 */
public class EsRequestWrapper extends HttpServletRequestWrapper {
    private final byte[] body;
    private final boolean containerManagedBody;

    public EsRequestWrapper(HttpServletRequest request) throws IOException {
        this(request, true);
    }

    private EsRequestWrapper(HttpServletRequest request, boolean captureBody) throws IOException {
        super(request);
        if (!captureBody) {
            body = null;
            containerManagedBody = false;
            return;
        }

        String contentType = request.getContentType();
        String mediaType = contentType == null ? "" : contentType.split(";", 2)[0].trim();
        containerManagedBody = "application/x-www-form-urlencoded".equalsIgnoreCase(mediaType)
                || mediaType.regionMatches(true, 0, "multipart/", 0, "multipart/".length());
        // Servlet containers must parse form fields and multipart parts from the original stream.
        // Reading it here first would hide login/token parameters and uploaded parts downstream.
        body = containerManagedBody ? new byte[0] : request.getInputStream().readAllBytes();
    }

    public static EsRequestWrapper metadataOnly(HttpServletRequest request) throws IOException {
        return new EsRequestWrapper(request, false);
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (body == null || containerManagedBody) {
            return super.getInputStream();
        }
        ByteArrayInputStream input = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return input.read();
            }

            @Override
            public boolean isFinished() {
                return input.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
            }
        };
    }

    @Override
    public BufferedReader getReader() throws IOException {
        if (body == null || containerManagedBody) {
            return super.getReader();
        }
        return new BufferedReader(new InputStreamReader(getInputStream(), bodyCharset()));
    }

    public String getBody() {
        return body == null ? null : new String(body, bodyCharset());
    }

    private Charset bodyCharset() {
        String encoding = getCharacterEncoding();
        if (encoding == null || encoding.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (RuntimeException invalidEncoding) {
            return StandardCharsets.UTF_8;
        }
    }
}
