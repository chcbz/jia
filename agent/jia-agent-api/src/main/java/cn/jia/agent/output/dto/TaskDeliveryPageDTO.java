package cn.jia.agent.output.dto;

import java.util.List;

public record TaskDeliveryPageDTO(
        List<TaskDeliveryViewDTO> items, String nextCursor, String snapshotAt) { }
