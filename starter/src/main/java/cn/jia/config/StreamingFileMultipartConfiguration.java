package cn.jia.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

/** Container spools files to disk; only the migrated binary routes bypass the previous envelope limits. */
@Configuration(proxyBeanMethods=false)
public class StreamingFileMultipartConfiguration {
    static final long LEGACY_FILE_BYTES=10L*1024*1024;
    static final long LEGACY_REQUEST_BYTES=50L*1024*1024;

    @Bean(name="multipartResolver")
    StandardServletMultipartResolver multipartResolver() {return new Resolver();}

    static boolean streamingFile(HttpServletRequest request) {
        if(!"POST".equals(request.getMethod()))return false;
        String path=request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/agent/personal-workspace/files")
                ||path.matches("/agent/personal-workspace/files/[^/]+/versions")
                ||path.matches("/internal/agent/tasks/[^/]+/runs/[^/]+/conversation/outputs/[^/]+/content");
    }
    static final class Resolver extends StandardServletMultipartResolver {
        @Override public MultipartHttpServletRequest resolveMultipart(HttpServletRequest request) {
            boolean streaming=streamingFile(request);
            if(!streaming&&request.getContentLengthLong()>LEGACY_REQUEST_BYTES)
                throw new MaxUploadSizeExceededException(LEGACY_REQUEST_BYTES);
            var parsed=super.resolveMultipart(request);
            try {
                // Eagerly parse, so limits precede any getBytes consumer. The container threshold is zero.
                if(!streaming) {
                    long total=0;
                    for(var part:request.getParts()) {
                        if(part.getSubmittedFileName()!=null&&part.getSize()>LEGACY_FILE_BYTES)
                            throw new MaxUploadSizeExceededException(LEGACY_FILE_BYTES);
                        total=Math.addExact(total,part.getSize());
                        if(total>LEGACY_REQUEST_BYTES)throw new MaxUploadSizeExceededException(LEGACY_REQUEST_BYTES);
                    }
                }
                return parsed;
            } catch(RuntimeException failure) {cleanupMultipart(parsed);throw failure;}
            catch(java.io.IOException|jakarta.servlet.ServletException failure) {
                cleanupMultipart(parsed);throw new org.springframework.web.multipart.MultipartException("Multipart input is unavailable",failure);
            }
        }
    }
}
