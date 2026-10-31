package cn.jia.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class StreamingFileMultipartConfigurationTest {
    @Test void onlyMigratedPostRoutesBypassOriginalLimits() {
        for(String path:new String[]{"/agent/personal-workspace/files","/agent/personal-workspace/files/file-1/versions",
                "/internal/agent/tasks/task/runs/run/conversation/outputs/output/content"}) {
            var request=new MockHttpServletRequest("POST",path);
            assertTrue(StreamingFileMultipartConfiguration.streamingFile(request));
            request.setMethod("GET");assertFalse(StreamingFileMultipartConfiguration.streamingFile(request));
        }
        for(String path:new String[]{"/user/avatar","/agent/skill-maintenance/product-versions",
                "/internal/agent/tasks/task/runs/run/outputs/output/content","/agent/personal-workspace/files/f/versions/extra"})
            assertFalse(StreamingFileMultipartConfiguration.streamingFile(new MockHttpServletRequest("POST",path)));
    }
    @Test void nonStreamingOversizedEnvelopeFailsBeforeContainerParsing() {
        var request=new MockHttpServletRequest("POST","/user/avatar") {
            @Override public long getContentLengthLong(){return StreamingFileMultipartConfiguration.LEGACY_REQUEST_BYTES+1;}
            @Override public java.util.Collection<jakarta.servlet.http.Part> getParts(){throw new AssertionError("Must reject before parsing");}
        };
        assertThrows(org.springframework.web.multipart.MaxUploadSizeExceededException.class,
                ()->new StreamingFileMultipartConfiguration.Resolver().resolveMultipart(request));
    }
    @Test void unknownLengthLegacyUploadStillChecksPartsAndCleansTemporaryFiles() throws Exception {
        var deleted=new java.util.concurrent.atomic.AtomicBoolean();
        var part=new org.springframework.mock.web.MockPart("file","large.bin",new byte[0]) {
            @Override public long getSize(){return StreamingFileMultipartConfiguration.LEGACY_FILE_BYTES+1;}
            @Override public void delete(){deleted.set(true);}
        };
        var request=new MockHttpServletRequest("POST","/user/avatar");
        request.setContentType("multipart/form-data; boundary=probe");request.addPart(part);
        assertThrows(org.springframework.web.multipart.MaxUploadSizeExceededException.class,
                ()->new StreamingFileMultipartConfiguration.Resolver().resolveMultipart(request));
        assertTrue(deleted.get());
    }
    @Test void streamedRouteAcceptsLargePartWithoutReadingBytesAndCleansOnCompletion() throws Exception {
        var deleted=new java.util.concurrent.atomic.AtomicBoolean();
        var part=new org.springframework.mock.web.MockPart("file","large.bin",new byte[0]) {
            @Override public long getSize(){return StreamingFileMultipartConfiguration.LEGACY_REQUEST_BYTES+1;}
            @Override public java.io.InputStream getInputStream(){throw new AssertionError("Resolver must not load the file");}
            @Override public void delete(){deleted.set(true);}
        };
        var request=new MockHttpServletRequest("POST","/agent/personal-workspace/files");
        request.setContentType("multipart/form-data; boundary=probe");request.addPart(part);
        var resolver=new StreamingFileMultipartConfiguration.Resolver();var parsed=resolver.resolveMultipart(request);
        assertEquals(part.getSize(),parsed.getFile("file").getSize());
        resolver.cleanupMultipart(parsed);assertTrue(deleted.get());
    }
}
