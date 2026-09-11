package cn.jia.agent.output.dto;

import java.io.InputStream;

public record OutputDownloadDTO(String fileName, String mimeType, long contentLength,
        String sha256, InputStream stream) { }
