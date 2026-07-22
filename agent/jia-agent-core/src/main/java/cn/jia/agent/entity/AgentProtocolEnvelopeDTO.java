package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * Transport-neutral Agent Protocol v1 envelope.
 *
 * <p>{@code sourceAgentId}/{@code targetAgentId} always refer to canonical,
 * stable domain identities. {@code runtimeInstanceId} identifies one client
 * process lifetime and must never be used as a task/member/ownership key.</p>
 */
@Data
public class AgentProtocolEnvelopeDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Integer schemaVersion;
    private String messageType;
    private String messageId;
    private String commandId;
    private String correlationId;
    private String causationId;

    private String tenantId;
    private String clientId;
    private String sourceAgentId;
    private String targetAgentId;
    private String runtimeInstanceId;

    private String conversationId;
    private String taskId;
    private String workItemId;
    private String commandType;

    private Long issuedAt;
    private Long sentAt;
    private Long expiresAt;
    private Integer attempt;
    private Map<String, Object> payload;
}
