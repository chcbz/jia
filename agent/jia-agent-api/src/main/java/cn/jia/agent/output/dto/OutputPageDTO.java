package cn.jia.agent.output.dto;

import java.util.List;

public record OutputPageDTO(List<OutputSummaryDTO> items, String nextCursor, String snapshotAt) { }
