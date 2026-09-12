package cn.jia.core.common;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.*;

/**
 * @author chc
 */
public class EsRequestWrapper extends HttpServletRequestWrapper {
	private final String body;
	private final boolean containerManagedBody;

	public EsRequestWrapper(HttpServletRequest request) throws IOException {
	   super(request);
		String contentType = request.getContentType();
		String mediaType = contentType == null ? "" : contentType.split(";", 2)[0].trim();
		containerManagedBody = "application/x-www-form-urlencoded".equalsIgnoreCase(mediaType)
		        || mediaType.regionMatches(true, 0, "multipart/", 0, "multipart/".length());
		// Servlet containers parse form parameters and multipart parts from the original stream.
		// Reading it here first irreversibly hides login/token parameters from Spring Security.
		// Leave parsing (including encoding, query merging and size limits) to the container.
		if (containerManagedBody) {
		    body = "";
		    return;
		}
	   StringBuilder stringBuilder = new StringBuilder();
	   BufferedReader bufferedReader = null;
	   try {
	     InputStream inputStream = request.getInputStream();
	     if (inputStream != null) {
	       bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
	       char[] charBuffer = new char[128];
	       int bytesRead = -1;
	       while ((bytesRead = bufferedReader.read(charBuffer)) > 0) {
	         stringBuilder.append(charBuffer, 0, bytesRead);
	       }
	     } else {
	       stringBuilder.append("");
	     }
	   } catch (IOException ex) {
	       throw ex;
	   } finally {
		     if (bufferedReader != null) {
		         try {
		           bufferedReader.close();
		         } catch (IOException ex) {
		           throw ex;
		         }
		       }
		     }
		     body = stringBuilder.toString();
		   }

	@Override
	public ServletInputStream getInputStream() throws IOException {
        if (containerManagedBody) {
            return super.getInputStream();
        }
		final ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(body.getBytes());
		return new ServletInputStream() {
			@Override
			public int read() throws IOException {
				return byteArrayInputStream.read();
			}

			@Override
			public boolean isFinished() {
				return false;
			}

			@Override
			public boolean isReady() {
				return false;
			}

			@Override
			public void setReadListener(ReadListener arg0) {

			}
		};
	}

	@Override
	public BufferedReader getReader() throws IOException {
        if (containerManagedBody) {
            return super.getReader();
        }
		return new BufferedReader(new InputStreamReader(this.getInputStream()));
	}

	public String getBody() {
		return this.body;
	}
}