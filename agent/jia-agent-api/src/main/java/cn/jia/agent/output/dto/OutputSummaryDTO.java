package cn.jia.agent.output.dto;

public record OutputSummaryDTO(OutputSourceDTO source, String outputId, String version,
        String title, String name, String mime, String size, String sha256, String createdAt,
        String state, String publicationKind, String previewKind, boolean canDownload,
        String producerName) { }
