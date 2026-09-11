package cn.jia.agent.output;

import cn.jia.agent.output.dto.OutputCapabilitiesDTO;
import cn.jia.agent.output.dto.OutputDetailDTO;
import cn.jia.agent.output.dto.OutputDownloadDTO;
import cn.jia.agent.output.dto.OutputPageDTO;
import cn.jia.agent.output.dto.OutputPublishDTO;
import cn.jia.agent.output.dto.OutputSummaryDTO;

public interface OutputDeliveryService {
    OutputCapabilitiesDTO capabilities();
    OutputSummaryDTO publish(String bearer, String idempotencyKey, String sourceType,
            String sourceId, OutputPublishDTO request);
    OutputPageDTO list(String tenantId, String clientId, String jiacn, String sourceType,
            String sourceId, String cursor, Integer limit);
    OutputPageDTO listVersions(String tenantId, String clientId, String jiacn,
            String sourceType, String sourceId, String outputId, String cursor, Integer limit);
    OutputDetailDTO getVersion(String tenantId, String clientId, String jiacn,
            String sourceType, String sourceId, String outputId, String version);
    OutputDownloadDTO downloadVersion(String tenantId, String clientId, String jiacn,
            String sourceType, String sourceId, String outputId, String version);
}
