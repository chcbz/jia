package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.agent.service.AgentCommandDlqRedriver;
import cn.jia.agent.service.AgentConfirmedRabbitPublisher;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pulls a bounded broker DLQ window without auto-ack and settles only after confirmed republish. */
public final class AgentCommandDlqRedriverImpl implements AgentCommandDlqRedriver {
    private final ConnectionFactory connectionFactory;
    private final AgentConfirmedRabbitPublisher publisher;

    public AgentCommandDlqRedriverImpl(
            ConnectionFactory connectionFactory,
            AgentConfirmedRabbitPublisher publisher) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    @Override
    public AgentRabbitPublishResult redrive(
            AgentConfirmedPublishRequest expected,
            long confirmTimeoutMillis,
            int scanLimit) {
        if (expected == null || confirmTimeoutMillis < 100 || confirmTimeoutMillis > 60_000
                || scanLimit < 1 || scanLimit > 500) {
            return failure("DLQ_REDRIVE_REQUEST_INVALID");
        }
        try {
            AgentCommandAmqpContract.validate(expected);
        } catch (IllegalArgumentException invalid) {
            return failure("DLQ_REDRIVE_PROVENANCE_INVALID");
        }

        Connection connection = null;
        Channel channel = null;
        List<Long> held = new ArrayList<>();
        Long targetTag = null;
        boolean targetSettled = false;
        AgentRabbitPublishResult result = failure("DLQ_MESSAGE_NOT_FOUND");
        try {
            connection = connectionFactory.createConnection();
            channel = connection.createChannel(false);
            for (int scanned = 0; scanned < scanLimit; scanned++) {
                GetResponse response = channel.basicGet(
                        AgentRabbitTopologyManifest.DEAD_LETTER_QUEUE, false);
                if (response == null) break;
                long tag = response.getEnvelope().getDeliveryTag();
                held.add(tag);
                if (response.getProps() == null
                        || !expected.messageId().equals(response.getProps().getMessageId())) continue;
                targetTag = tag;
                AgentConfirmedPublishRequest brokerExpected = brokerExpected(expected, response);
                if (brokerExpected == null) {
                    result = failure("DLQ_MESSAGE_PROVENANCE_INVALID");
                    break;
                }
                AgentRabbitPublishResult invalid = validateBrokerMessage(brokerExpected, response);
                if (invalid != null) {
                    result = invalid;
                    break;
                }
                Map<String, Object> brokerHeaders = response.getProps().getHeaders();
                try {
                    AgentConfirmedRabbitPublisherImpl.validatePreservedHeaders(
                            brokerExpected, brokerHeaders);
                } catch (RuntimeException headerDrift) {
                    result = failure("DLQ_MESSAGE_PROVENANCE_INVALID");
                    break;
                }
                result = publisher.publishPreservingHeaders(
                        brokerExpected, brokerHeaders, confirmTimeoutMillis);
                if (result != null && result.type() == AgentRabbitPublishResult.Type.ACK) {
                    channel.basicAck(tag, false);
                    targetSettled = true;
                }
                if (result == null) result = failure("DLQ_REDRIVE_PUBLISH_FAILED");
                break;
            }
        } catch (Exception ignored) {
            result = failure("DLQ_BROKER_FAILURE");
        }

        boolean settlementProven = settleHeld(channel, held, targetTag, targetSettled);
        boolean channelClosed = closeChannel(channel);
        closeConnection(connection);
        if (!settlementProven || !channelClosed) return failure("DLQ_BROKER_FAILURE");
        return result;
    }

    private static boolean settleHeld(
            Channel channel, List<Long> held, Long targetTag, boolean targetSettled) {
        if (channel == null || held.isEmpty()) return true;
        for (Long tag : held) {
            if (targetSettled && Objects.equals(tag, targetTag)) continue;
            try {
                channel.basicNack(tag, false, true);
            } catch (IOException | RuntimeException settlementFailure) {
                return false;
            }
        }
        try {
            // basicAck/basicNack are one-way methods. A same-channel RPC barrier proves that all
            // prior settlements reached the broker before a cached channel can be released.
            channel.queueDeclarePassive(AgentRabbitTopologyManifest.DEAD_LETTER_QUEUE);
            return true;
        } catch (IOException | RuntimeException settlementFailure) {
            return false;
        }
    }

    private AgentRabbitPublishResult validateBrokerMessage(
            AgentConfirmedPublishRequest expected, GetResponse response) {
        AMQP.BasicProperties properties = response.getProps();
        if (properties == null || response.getEnvelope() == null
                || !AgentRabbitTopologyManifest.DEAD_LETTER_EXCHANGE.equals(
                        response.getEnvelope().getExchange())
                || !AgentRabbitTopologyManifest.DEAD_ROUTING_KEY.equals(
                        response.getEnvelope().getRoutingKey())
                || response.getBody() == null
                || !MessageDigest.isEqual(expected.wirePayload(), response.getBody())
                || !AgentCommandAmqpContract.CONTENT_TYPE.equals(properties.getContentType())
                || !AgentCommandAmqpContract.CONTENT_ENCODING.equals(properties.getContentEncoding())
                || !Integer.valueOf(2).equals(properties.getDeliveryMode())
                || !expected.messageId().equals(properties.getMessageId())
                || !AgentCommandAmqpContract.MESSAGE_TYPE.equals(properties.getType())
                || properties.getPriority() != null || properties.getTimestamp() != null
                || properties.getCorrelationId() != null || properties.getReplyTo() != null
                || properties.getExpiration() != null || properties.getUserId() != null
                || properties.getAppId() != null || properties.getClusterId() != null) {
            return failure("DLQ_MESSAGE_PROVENANCE_INVALID");
        }
        return null;
    }

    private AgentConfirmedPublishRequest brokerExpected(
            AgentConfirmedPublishRequest expected, GetResponse response) {
        try {
            Object value = response.getProps().getHeaders()
                    .get(AgentCommandAmqpContract.HEADER_SOURCE_SETTLEMENT_RETRY);
            if (!(value instanceof Number number)
                    || !(value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long)) return null;
            long retry = number.longValue();
            if (retry < 0 || retry > 1_000) return null;
            return new AgentConfirmedPublishRequest(
                    expected.destination(), expected.routingKey(), expected.wirePayload(),
                    expected.wirePayloadHash(), expected.messageId(), expected.eventId(),
                    expected.deliveryId(), expected.commandId(), expected.tenantId(),
                    expected.clientId(), expected.taskId(), expected.targetAgentId(),
                    expected.commandType(), expected.activeAttempt(), expected.expiresAt(),
                    expected.topologySha256(), (int) retry);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static AgentRabbitPublishResult failure(String code) {
        return new AgentRabbitPublishResult(
                AgentRabbitPublishResult.Type.EXCEPTION,
                "NONE", "NOT_RETURNED", null, null, code);
    }

    private static void closeConnection(Connection connection) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (RuntimeException ignored) {
            // Operation result is already sanitized and authoritative.
        }
    }

    private static boolean closeChannel(Channel channel) {
        if (channel == null) return true;
        try {
            channel.close();
            return true;
        } catch (Exception closeFailure) {
            return false;
        }
    }
}
