package cn.jia.agent.entity;

/** Durable write outcome; duplicates intentionally have no new outbox identity. */
public record AgentCommandTransportWriteResult(
        long deliveryId,
        String commandId,
        String messageId,
        String outboxEventId,
        boolean duplicate) {
}
