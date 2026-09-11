package cn.jia.agent.output;

import cn.jia.agent.output.dto.OutputUploadCreateDTO;
import cn.jia.agent.output.dto.OutputUploadDTO;

import java.io.InputStream;

public interface OutputUploadService {
    OutputUploadDTO create(String bearer, String idempotencyKey, OutputUploadCreateDTO request);
    OutputUploadDTO put(String bearer, String uploadId, InputStream bytes);
    OutputUploadDTO complete(String bearer, String uploadId, String idempotencyKey);
    OutputUploadDTO status(String bearer, String uploadId);
    int recover(int limit);
}
