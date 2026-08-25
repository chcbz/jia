package cn.jia.agent.entity;

/** Durable D06 manual reissue result; Rabbit publication remains owned by D03 Relay. */
public record AgentCommandManualReissueResult(
        long deliveryId,
        String commandId,
        String sourceMessageId,
        String newMessageId,
        String newEventId,
        int sourceAttempt,
        int newAttempt) {
}
