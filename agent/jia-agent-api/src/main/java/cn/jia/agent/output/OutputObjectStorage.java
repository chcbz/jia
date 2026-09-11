package cn.jia.agent.output;

import java.io.IOException;
import java.io.InputStream;

public interface OutputObjectStorage {
    record Stored(String versionId, String etag) { }
    record Head(long size, String versionId, String cleanupToken) { }

    Stored putCreateOnly(String bucket, String key, InputStream input, long length,
                         String contentType, long deadlineMillis) throws IOException;
    InputStream open(String bucket, String key, String versionId) throws IOException;
    void putTombstone(String bucket, String key, String cleanupToken,
                      long deadlineMillis) throws IOException;
    Head head(String bucket, String key) throws IOException;
    void delete(String bucket, String key, String versionId) throws IOException;
}
