package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import cn.jia.agent.entity.AgentOutboxClaimToken;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.agent.service.AgentConfirmedRabbitPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRabbitOutboxPublisherTest {
    @Test
    void adapterUsesDeliveryTransportAttemptNotOutboxPublishAttemptAndKeepsRawBytes() {
        AgentRabbitTopologyManifest manifest = AgentRabbitTopologyManifest.canonical();
        byte[] wire = ("{\"schemaVersion\":1,\"messageType\":\"command.dispatch\"," +
                "\"messageId\":\"msg-1\",\"commandId\":\"cmd-1\"," +
                "\"tenantId\":\"tenant-a\",\"clientId\":\"client-a\"," +
                "\"taskId\":\"task-1\",\"targetAgentId\":\"agent-1\"," +
                "\"commandType\":\"task.invite\",\"attempt\":2,\"expiresAt\":9999999}")
                .getBytes(StandardCharsets.UTF_8);
        AgentOutboxClaimToken token = new AgentOutboxClaimToken(
                1, 41, "tenant-a", "client-a", "evt-1", "msg-1", "cmd-1",
                "task-1", "agent-1", "task.invite",
                manifest.defaultCommandPublishRoute().destination(),
                manifest.defaultCommandPublishRoute().routingKey(), wire,
                AgentCommandAmqpContract.sha256(wire), 9_999_999L, "lease", 1234,
                9, 5, "RETRY", "msg-1", 2, 7);
        AgentConfirmedRabbitPublisher primitive = mock(AgentConfirmedRabbitPublisher.class);
        when(primitive.publish(any(), eq(5000L))).thenReturn(AgentRabbitPublishResult.ack());

        new AgentRabbitOutboxPublisher(primitive, manifest).publish(token, 5000);

        ArgumentCaptor<AgentConfirmedPublishRequest> request =
                ArgumentCaptor.forClass(AgentConfirmedPublishRequest.class);
        verify(primitive).publish(request.capture(), eq(5000L));
        assertEquals(2, request.getValue().activeAttempt());
        assertEquals(0, request.getValue().sourceSettlementRetry());
        assertEquals("msg-1", request.getValue().messageId());
        assertArrayEquals(wire, request.getValue().wirePayload());
    }
}
