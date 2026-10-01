package cn.jia.chat.archive.maintenance.model;

/** Trusted request context. None of these values are accepted as model tool arguments. */
public record ArchiveRequestContext(
        ArchiveActorScope actorScope,
        String requestIntentId,
        String entryPoint,
        String conversationRef,
        String targetAgentId,
        ArchiveConfirmedPolicyRef confirmedPolicyRef) { }
