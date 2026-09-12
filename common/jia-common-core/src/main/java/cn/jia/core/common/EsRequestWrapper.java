package cn.jia.core.common;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * @author chc
 */
public class EsRequestWrapper extends HttpServletRequestWrapper {
	private final byte[] body;

	public EsRequestWrapper(HttpServletRequest request) throws IOException {
		this(request, true);
	}

	private EsRequestWrapper(HttpServletRequest request, boolean captureBody) throws IOException {
		super(request);
		body = captureBody ? request.getInputStream().readAllBytes() : null;
	}

	public static EsRequestWrapper metadataOnly(HttpServletRequest request) throws IOException {
		return new EsRequestWrapper(request, false);
	}

	@Override
	public ServletInputStream getInputStream() throws IOException {
		if (body == null) {
			return super.getInputStream();
		}
		final ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(body);
		return new ServletInputStream() {
			@Override
			public int read() throws IOException {
				return byteArrayInputStream.read();
			}

			@Override
			public boolean isFinished() {
				return byteArrayInputStream.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener arg0) {

			}
		};
	}

	@Override
	public BufferedReader getReader() throws IOException {
		if (body == null) {
			return super.getReader();
		}
		return new BufferedReader(new InputStreamReader(this.getInputStream(), bodyCharset()));
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
