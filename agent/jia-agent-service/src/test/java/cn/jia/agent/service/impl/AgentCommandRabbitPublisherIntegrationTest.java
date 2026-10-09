package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.config.AgentRabbitDispatchScopeProperties;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.config.AgentRabbitTopologyConfiguration;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.config.AgentRabbitTopologyReadiness;
import cn.jia.agent.config.AgentRabbitV2TopologyManifest;
import cn.jia.agent.config.AgentRabbitV2TopologyReadiness;
import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.agent.service.AgentCommandInboxService;
import cn.jia.agent.service.AgentRawCommandDispatcher;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCommandRabbitPublisherIntegrationTest {
    private static final long NOW = 1_000_000L;
    private static final long EXPIRES = 2_000_000L;
    private static final AgentRabbitTopologyManifest LEGACY =
            AgentRabbitTopologyManifest.canonical();
    private static final AgentRabbitV2TopologyManifest SCOPED =
            AgentRabbitV2TopologyManifest.forPersistedScope("1", "cyf", "pool-a");

    @Test
    void legacyConsumerRetryAndTerminalParkingUseRealConfirmedPublisher() throws Exception {
        RabbitTemplate template = ackingTemplate();
        AgentConfirmedRabbitPublisherImpl publisher = publisher(
                template, legacyReadiness(true), v2Readiness(true));
        AgentCommandInboxService inbox = mock(AgentCommandInboxService.class);
        when(inbox.claim(any(), any(), anyLong(), anyLong()))
                .thenThrow(new cn.jia.agent.entity.AgentInboxSourceNotSettledException(
                        "OUTBOX_NOT_PUBLISHED"));
        AgentCommandRabbitConsumer consumer = consumer(inbox, publisher);

        Channel retryChannel = mock(Channel.class);
        consumer.consume(legacyMessage(0, 71L), retryChannel);
        Channel deadChannel = mock(Channel.class);
        consumer.consume(legacyMessage(
                AgentCommandRabbitConsumer.MAX_SOURCE_SETTLEMENT_RETRIES, 72L), deadChannel);

        ArgumentCaptor<Message> messages = ArgumentCaptor.forClass(Message.class);
        verify(template).send(eq(AgentRabbitTopologyManifest.DEAD_LETTER_EXCHANGE),
                eq(AgentRabbitTopologyManifest.RETRY_5S_ROUTING_KEY),
                messages.capture(), any(CorrelationData.class));
        verify(template).send(eq(AgentRabbitTopologyManifest.DEAD_LETTER_EXCHANGE),
                eq(AgentRabbitTopologyManifest.DEAD_ROUTING_KEY),
                messages.capture(), any(CorrelationData.class));
        for (Message sent : messages.getAllValues()) {
            assertEquals(13, sent.getMessageProperties().getHeaders().size());
            assertEquals(Integer.valueOf(1), sent.getMessageProperties().getHeaders().get(
                    AgentCommandAmqpContract.HEADER_WIRE_VERSION));
            assertFalse(sent.getMessageProperties().getHeaders().containsKey(
                    AgentCommandAmqpContract.HEADER_DATA_DOMAIN_ID));
        }
        verify(retryChannel).basicAck(71L, false);
        verify(deadChannel).basicAck(72L, false);
    }

    @Test
    void legacyReadinessFailureAndCrossProtocolRoutesMakeZeroRabbitCalls() throws Exception {
        RabbitTemplate template = ackingTemplate();
        AgentConfirmedRabbitPublisherImpl notReady = publisher(
                template, legacyReadiness(false), v2Readiness(true));
        AgentCommandInboxService inbox = mock(AgentCommandInboxService.class);
        when(inbox.claim(any(), any(), anyLong(), anyLong()))
                .thenThrow(new cn.jia.agent.entity.AgentInboxSourceNotSettledException(
                        "OUTBOX_NOT_PUBLISHED"));
        Channel channel = mock(Channel.class);

        consumer(inbox, notReady).consume(legacyMessage(0, 73L), channel);

        verify(template, never()).send(any(), any(), any(Message.class), any(CorrelationData.class));
        verify(channel).basicNack(73L, false, true);

        AgentConfirmedRabbitPublisherImpl ready = publisher(
                template, legacyReadiness(true), v2Readiness(true));
        AgentRabbitPublishResult mixedV1 = ready.publish(legacyRequestWithV2Provenance(), 1_000);
        AgentRabbitPublishResult crossedV2 = ready.publish(v2RequestOnLegacyRoute(), 1_000);

        assertEquals("PUBLISH_PROVENANCE_INVALID", mixedV1.errorCode());
        assertEquals("DESTINATION_POLICY_REJECTED", crossedV2.errorCode());
        verify(template, never()).send(any(), any(), any(Message.class), any(CorrelationData.class));
    }

    private AgentCommandRabbitConsumer consumer(
            AgentCommandInboxService inbox, AgentConfirmedRabbitPublisherImpl publisher) {
        return new AgentCommandRabbitConsumer(inbox,
                mock(AgentTaskCollaborationAccessService.class),
                mock(AgentRawCommandDispatcher.class), publisher, gate(), LEGACY,
                () -> NOW, "legacy-composition-test");
    }

    private AgentConfirmedRabbitPublisherImpl publisher(
            RabbitTemplate template, AgentRabbitTopologyReadiness legacyReadiness,
            AgentRabbitV2TopologyReadiness v2Readiness) {
        return new AgentConfirmedRabbitPublisherImpl(
                template, gate(), LEGACY, legacyReadiness, v2Readiness);
    }

    private Message legacyMessage(int sourceRetry, long deliveryTag) {
        AgentConfirmedPublishRequest request = legacyRequest(
                AgentRabbitTopologyManifest.MAIN_EXCHANGE,
                AgentRabbitTopologyManifest.GENERAL_ROUTING_KEY, sourceRetry);
        MessageProperties properties = new MessageProperties();
        properties.setContentType(AgentCommandAmqpContract.CONTENT_TYPE);
        properties.setContentEncoding(AgentCommandAmqpContract.CONTENT_ENCODING);
        properties.setType(AgentCommandAmqpContract.MESSAGE_TYPE);
        properties.setMessageId(request.messageId());
        properties.setReceivedDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setConsumerQueue(AgentRabbitTopologyManifest.DISPATCH_QUEUE);
        properties.setReceivedExchange(AgentRabbitTopologyManifest.MAIN_EXCHANGE);
        properties.setReceivedRoutingKey(AgentRabbitTopologyManifest.GENERAL_ROUTING_KEY);
        properties.setDeliveryTag(deliveryTag);
        properties.setHeaders(new HashMap<>(AgentCommandAmqpContract.headers(request)));
        return new Message(request.wirePayload(), properties);
    }

    private AgentConfirmedPublishRequest legacyRequest(
            String destination, String routingKey, int sourceRetry) {
        byte[] body = ("{\"schemaVersion\":1,\"messageType\":\"command.dispatch\","
                + "\"messageId\":\"msg-1\",\"commandId\":\"cmd-1\","
                + "\"tenantId\":\"0\",\"clientId\":\"client-a\","
                + "\"ownerJiacn\":\"owner-a\",\"taskId\":\"task-1\","
                + "\"targetAgentId\":\"agent-1\",\"commandType\":\"TASK_INVITE\","
                + "\"attempt\":1,\"expiresAt\":" + EXPIRES + ","
                + "\"payload\":{\"instruction\":\"execute\"}}")
                .getBytes(StandardCharsets.UTF_8);
        return new AgentConfirmedPublishRequest(destination, routingKey, body,
                AgentCommandAmqpContract.sha256(body), "msg-1", "evt-1", 41L,
                "cmd-1", "0", "client-a", "task-1", "agent-1",
                AgentProtocolConstants.COMMAND_TASK_INVITE, 1, EXPIRES,
                LEGACY.sha256(), sourceRetry);
    }

    private AgentConfirmedPublishRequest legacyRequestWithV2Provenance() {
        AgentConfirmedPublishRequest base = legacyRequest(
                SCOPED.mainExchange(), SCOPED.generalRoutingKey(), 0);
        return new AgentConfirmedPublishRequest(base.destination(), base.routingKey(),
                base.wirePayload(), base.wirePayloadHash(), base.messageId(), base.eventId(),
                base.deliveryId(), base.commandId(), base.tenantId(), "cyf", "pool-a", 3,
                "deployment-a", "agent-command", 4, 1, base.clientId(), base.taskId(),
                base.targetAgentId(), base.commandType(), base.activeAttempt(), base.expiresAt(),
                SCOPED.sha256(), base.sourceSettlementRetry());
    }

    private AgentConfirmedPublishRequest v2RequestOnLegacyRoute() {
        byte[] body = ("{\"schemaVersion\":2,\"messageType\":\"command.dispatch\","
                + "\"messageId\":\"v2-msg\",\"commandId\":\"v2-cmd\",\"tenantId\":\"1\","
                + "\"dataDomainId\":\"cyf\",\"executionPoolId\":\"pool-a\","
                + "\"producerDeploymentId\":\"deployment-a\",\"admissionEpoch\":3,"
                + "\"moduleId\":\"agent-command\",\"migrationEpoch\":4,"
                + "\"deliveryId\":\"42\","
                + "\"clientId\":\"client-a\",\"taskId\":\"task-1\","
                + "\"targetAgentId\":\"agent-1\",\"commandType\":\"TASK_INVITE\","
                + "\"attempt\":1,\"expiresAt\":" + EXPIRES + ",\"payload\":{}}")
                .getBytes(StandardCharsets.UTF_8);
        return new AgentConfirmedPublishRequest(AgentRabbitTopologyManifest.MAIN_EXCHANGE,
                AgentRabbitTopologyManifest.GENERAL_ROUTING_KEY, body,
                AgentCommandAmqpContract.sha256(body), "v2-msg", "v2-event", 42L, "v2-cmd",
                "1", "cyf", "pool-a", 3, "deployment-a", "agent-command", 4, 2,
                "client-a", "task-1", "agent-1", AgentProtocolConstants.COMMAND_TASK_INVITE,
                1, EXPIRES, SCOPED.sha256(), 0);
    }

    private RabbitTemplate ackingTemplate() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            invocation.<CorrelationData>getArgument(3).getFuture()
                    .complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(any(), any(), any(Message.class), any(CorrelationData.class));
        return template;
    }

    private AgentRabbitTopologyReadiness legacyReadiness(boolean ready) throws Exception {
        AgentRabbitTopologyReadiness readiness =
                new AgentRabbitTopologyConfiguration().agentRabbitTopologyReadiness(LEGACY);
        if (ready) {
            Method mark = AgentRabbitTopologyReadiness.class.getDeclaredMethod("markProvisioned");
            mark.setAccessible(true);
            mark.invoke(readiness);
        }
        return readiness;
    }

    private AgentRabbitV2TopologyReadiness v2Readiness(boolean ready) throws Exception {
        AgentRabbitV2TopologyReadiness readiness = new AgentRabbitV2TopologyReadiness();
        if (ready) {
            Method mark = AgentRabbitV2TopologyReadiness.class.getDeclaredMethod(
                    "markProvisioned", AgentRabbitV2TopologyManifest.class);
            mark.setAccessible(true);
            mark.invoke(readiness, SCOPED);
        }
        return readiness;
    }

    private AgentRabbitSafetyGate gate() {
        AgentRabbitSafetyProperties properties = new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(true),
                new AgentRabbitSafetyProperties.RabbitTopology(true),
                new AgentRabbitSafetyProperties.RabbitPublish(true),
                new AgentRabbitSafetyProperties.RabbitConsume(true),
                new AgentRabbitSafetyProperties.RabbitDispatch(true),
                new AgentRabbitSafetyProperties.RabbitBroker(
                        "isolated.invalid", 35672, "user", "secret", "/isolated"));
        return new AgentRabbitSafetyGate(properties,
                new AgentRabbitDispatchScopeProperties(List.of(
                        new AgentRabbitDispatchScopeProperties.AllowedScope("0", "client-a"),
                        new AgentRabbitDispatchScopeProperties.AllowedScope("1", "client-a")),
                        List.of(new AgentRabbitDispatchScopeProperties.AllowedWorkerScope(
                                "1", "cyf", "pool-a"))));
    }
}
