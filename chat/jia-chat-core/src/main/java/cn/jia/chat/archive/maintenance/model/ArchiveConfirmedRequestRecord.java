package cn.jia.chat.archive.maintenance.model;

/** Immutable confirmed business request with an optional one-time canonical chat-turn binding. */
public record ArchiveConfirmedRequestRecord(
        String confirmationRef,
        String requestIntentId,
        String tenantId,
        String clientId,
        String ownerJiacn,
        String collectionId,
        String requestJson,
        String requestSha256,
        String publicationModeCeiling,
        String conversationId,
        String canonicalMessageId,
        Long conversationGeneration,
        String turnSha256,
        String entryPoint,
        String targetAgentId,
        long revision) { }
