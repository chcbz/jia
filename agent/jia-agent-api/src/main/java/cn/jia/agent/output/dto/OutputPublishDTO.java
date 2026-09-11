package cn.jia.agent.output.dto;

public record OutputPublishDTO(String runId, String expectedPreviousVersion, String title,
        String artifactType, String content, String objectId, String outputId, String version,
        String workItemId, String visibility, Boolean publishToOwner) { }
