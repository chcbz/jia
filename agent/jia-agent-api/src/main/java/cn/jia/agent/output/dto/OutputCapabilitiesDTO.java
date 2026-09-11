package cn.jia.agent.output.dto;

import java.util.List;

public record OutputCapabilitiesDTO(boolean outputUploadV1, boolean outputReadV1,
        boolean taskOwnerShareV1, boolean taskDeliveryHttpV1, String maxFileBytes,
        String maxRunBytes, int maxFiles, List<String> supportedMimeTypes) { }
