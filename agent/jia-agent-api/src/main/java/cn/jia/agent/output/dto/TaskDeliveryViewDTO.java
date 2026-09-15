package cn.jia.agent.output.dto;

import java.util.List;

public record TaskDeliveryViewDTO(
        String deliveryId, String taskId, String revision, String version,
        String taskVersion, String state, String summary,
        List<TaskDeliveryViewItemDTO> items, List<String> reviewActions) { }
