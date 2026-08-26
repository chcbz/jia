package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.config.AgentRabbitDispatchScopeProperties;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.config.AgentRabbitTopologyConfiguration;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.config.AgentRabbitTopologyProvisioner;
import cn.jia.agent.config.AgentRabbitTopologyReadiness;
import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.agent.service.AgentConfirmedRabbitPublisher;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.LongString;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Real-broker D09 gate. It is intentionally focused, non-skippable, and exact-owned. */
class AgentCommandDlqRedriverRabbitIntegrationTest {
    private static final String IMAGE = "rabbitmq:4.1-management-alpine";
    private static final String FIXTURE_CONTRACT = """
            d09-rabbit-redrive-integration/v1
            image=rabbitmq:4.1-management-alpine
            lifecycle=explicit-test-owned-container
            network=bridge-random-host-port
            identity=random-user-password-non-default-vhost
            topology=d04-canonical-explicit-provision
            source=basic-get-no-auto-ack
            settle=ack-only-after-confirmed-not-returned
            failure=return-nack-exception-timeout-requeue
            scan=bounded-non-target-requeue
            host-listener=proc-net-5672-before-after
            """;
    private static final String FIXTURE_SHA256 =
            "faead0f34dfd83015ad4af360929811317645631674610da9c2d4a8ad9693b04";
    private static final AgentRabbitTopologyManifest MANIFEST =
            AgentRabbitTopologyManifest.canonical();
    private static final AtomicInteger REQUEST_SEQUENCE = new AtomicInteger();

    private static RabbitMQContainer broker;
    private static CachingConnectionFactory connectionFactory;
    private static AgentRabbitTopologyProvisioner provisioner;
    private static AgentConfirmedRabbitPublisher confirmedPublisher;
    private static String runId;
    private static String username;
    private static String password;
    private static String virtualHost;
    private static String containerId;
    private static String imageId;
    private static int mappedAmqpPort;
    private static List<String> host5672Before;

    @BeforeAll
    static void startExactOwnedBroker() throws Exception {
        assertEquals(FIXTURE_SHA256, sha256(FIXTURE_CONTRACT));
        assertEquals("96fd7d32aba468eacbcf96dfa5d441fd938f0d0cb5c6dafcdcae9e797fb4110e",
                MANIFEST.sha256());
        host5672Before = hostListenerSnapshot(5672);

        runId = UUID.randomUUID().toString().replace("-", "");
        username = "d09_" + runId.substring(0, 16);
        password = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        virtualHost = "/d09-" + runId;
        broker = new RabbitMQContainer(DockerImageName.parse(IMAGE))
                .withAdminUser(username)
                .withAdminPassword(password)
                .withEnv("RABBITMQ_DEFAULT_VHOST", virtualHost)
                .withLabel("cyf.task", "D09")
                .withLabel("cyf.fixture", "AgentCommandDlqRedriverRabbitIntegrationTest")
                .withLabel("cyf.run", runId);
        assertTrue(broker.getPortBindings().isEmpty());
        assertNotEquals("host", broker.getNetworkMode());

        try {
            broker.start();
            containerId = broker.getContainerId();
            mappedAmqpPort = broker.getMappedPort(5672);
            assertTrue(mappedAmqpPort > 0);
            assertNotEquals(5672, mappedAmqpPort);
            assertNotEquals("/", virtualHost);
            assertNotEquals("guest", username);
            assertEquals(host5672Before, hostListenerSnapshot(5672));

            DockerClient docker = DockerClientFactory.instance().client();
            InspectContainerResponse inspected = docker.inspectContainerCmd(containerId).exec();
            imageId = inspected.getImageId();
            assertNotNull(imageId);
            assertEquals("D09", inspected.getConfig().getLabels().get("cyf.task"));
            assertEquals(runId, inspected.getConfig().getLabels().get("cyf.run"));
            assertBrokerIdentityInsideContainer();

            connectionFactory = new CachingConnectionFactory(
                    broker.getHost(), mappedAmqpPort);
            connectionFactory.setUsername(username);
            connectionFactory.setPassword(password);
            connectionFactory.setVirtualHost(virtualHost);
            connectionFactory.setPublisherConfirmType(
                    CachingConnectionFactory.ConfirmType.CORRELATED);
            connectionFactory.setPublisherReturns(true);

            RabbitAdmin admin = new RabbitAdmin(connectionFactory);
            admin.setAutoStartup(false);
            admin.setExplicitDeclarationsOnly(true);
            AgentRabbitTopologyConfiguration topology =
                    new AgentRabbitTopologyConfiguration();
            AgentRabbitTopologyReadiness readiness =
                    topology.agentRabbitTopologyReadiness(MANIFEST);
            provisioner = topology.agentRabbitTopologyProvisioner(
                    MANIFEST, admin, connectionFactory, readiness);
            provisioner.provision();

            RabbitTemplate template = new RabbitTemplate(connectionFactory);
            template.setMandatory(true);
            template.setChannelTransacted(false);
            confirmedPublisher = new AgentConfirmedRabbitPublisherImpl(
                    template, allowedGate(), MANIFEST, readiness);
            System.out.printf(
                    "D09_RABBIT_FIXTURE fixtureSha256=%s topologySha256=%s image=%s "
                            + "imageId=%s containerId=%s mappedAmqpPort=%d vhost=%s user=%s "
                            + "host5672Before=%s%n",
                    FIXTURE_SHA256, MANIFEST.sha256(), IMAGE, imageId, containerId,
                    mappedAmqpPort, virtualHost, username, host5672Before);
        } catch (Throwable failure) {
            stopOwnedBroker();
            throw failure;
        }
    }

    @AfterAll
    static void stopExactOwnedBrokerAndVerifyHostListener() throws Exception {
        Throwable cleanupFailure = null;
        try {
            if (connectionFactory != null) {
                connectionFactory.destroy();
            }
        } catch (Throwable failure) {
            cleanupFailure = failure;
        }
        try {
            stopOwnedBroker();
        } catch (Throwable failure) {
            if (cleanupFailure == null) cleanupFailure = failure;
            else cleanupFailure.addSuppressed(failure);
        }

        List<String> host5672After = hostListenerSnapshot(5672);
        assertEquals(host5672Before, host5672After,
                "D09 must not change the host/default Rabbit 5672 listener");
        if (containerId != null) {
            List<?> remaining = DockerClientFactory.instance().client().listContainersCmd()
                    .withShowAll(true).withIdFilter(List.of(containerId)).exec();
            assertTrue(remaining.isEmpty(), "exact-owned Rabbit container must be removed");
        }
        System.out.printf(
                "D09_RABBIT_CLEANUP containerId=%s removed=true host5672After=%s%n",
                containerId, host5672After);
        if (cleanupFailure != null) {
            throw new AssertionError("D09 exact-owned Rabbit cleanup failed", cleanupFailure);
        }
    }

    @BeforeEach
    void resetCanonicalTopology() throws Exception {
        provisioner.provision();
        withChannel(channel -> {
            bindGeneralRoute(channel);
            for (AgentRabbitTopologyManifest.QueueSpec queue : MANIFEST.queues()) {
                channel.queuePurge(queue.name());
            }
        });
    }

    @AfterEach
    void purgeOwnedQueuesAndRestoreBinding() throws Exception {
        withChannel(channel -> {
            bindGeneralRoute(channel);
            for (AgentRabbitTopologyManifest.QueueSpec queue : MANIFEST.queues()) {
                channel.queuePurge(queue.name());
            }
        });
    }

    @Test
    void isolatedBrokerUsesRandomPortDedicatedIdentityAndCanonicalTopology() throws Exception {
        assertTrue(broker.isRunning());
        assertNotNull(containerId);
        assertTrue(containerId.matches("[0-9a-f]{12,64}"));
        assertNotEquals(5672, mappedAmqpPort);
        assertNotEquals("/", virtualHost);
        assertTrue(virtualHost.startsWith("/d09-"));
        assertTrue(password.length() >= 64);
        assertEquals(host5672Before, hostListenerSnapshot(5672));

        withChannel(channel -> {
            for (AgentRabbitTopologyManifest.ExchangeSpec exchange : MANIFEST.exchanges()) {
                channel.exchangeDeclarePassive(exchange.name());
            }
            for (AgentRabbitTopologyManifest.QueueSpec queue : MANIFEST.queues()) {
                AMQP.Queue.DeclareOk declared = channel.queueDeclarePassive(queue.name());
                assertEquals(0, declared.getMessageCount(), queue.name());
                assertEquals(0, declared.getConsumerCount(), queue.name());
            }
        });
    }

    @Test
    void confirmedRedriveAcksSourceAndPreservesMessageIdRawBodyHashAndCanonicalHeaders()
            throws Exception {
        AgentConfirmedPublishRequest expected = request("confirmed");
        Map<String, Object> sourceHeaders = seedDlq(expected);

        AgentRabbitPublishResult result = redriver(confirmedPublisher)
                .redrive(expected, 5_000L, 1);

        assertEquals(AgentRabbitPublishResult.Type.ACK, result.type());
        assertEquals(0, queueCount(AgentRabbitTopologyManifest.DEAD_LETTER_QUEUE));
        GetResponse republished = take(AgentRabbitTopologyManifest.DISPATCH_QUEUE, true);
        assertNotNull(republished);
        assertEquals(AgentRabbitTopologyManifest.MAIN_EXCHANGE,
                republished.getEnvelope().getExchange());
        assertEquals(AgentRabbitTopologyManifest.GENERAL_ROUTING_KEY,
                republished.getEnvelope().getRoutingKey());
        assertArrayEquals(expected.wirePayload(), republished.getBody());
        assertArrayEquals(expected.wirePayloadHash(),
                AgentCommandAmqpContract.sha256(republished.getBody()));
        assertEquals(expected.messageId(), republished.getProps().getMessageId());
        assertEquals(AgentCommandAmqpContract.CONTENT_TYPE,
                republished.getProps().getContentType());
        assertEquals(AgentCommandAmqpContract.CONTENT_ENCODING,
                republished.getProps().getContentEncoding());
        assertEquals(Integer.valueOf(2), republished.getProps().getDeliveryMode());
        assertEquals(AgentCommandAmqpContract.MESSAGE_TYPE,
                republished.getProps().getType());
        assertCanonicalHeadersPreserved(sourceHeaders, republished.getProps().getHeaders());
        assertEquals(AgentCommandAmqpContract.hex(expected.wirePayloadHash()),
                headerText(republished.getProps().getHeaders().get(
                        AgentCommandAmqpContract.HEADER_WIRE_SHA256)));
        assertNull(take(AgentRabbitTopologyManifest.DISPATCH_QUEUE, true));
    }

    @Test
    void mandatoryReturnDoesNotAckAndRequeuesSource() throws Exception {
        AgentConfirmedPublishRequest expected = request("returned");
        seedDlq(expected);
        unbindGeneralRoute();
        try {
            AgentRabbitPublishResult result = redriver(confirmedPublisher)
                    .redrive(expected, 5_000L, 1);

            assertEquals(AgentRabbitPublishResult.Type.RETURNED, result.type());
            assertEquals("RETURNED", result.returnStatus());
            assertEquals(Integer.valueOf(312), result.returnReplyCode());
            assertEquals(1, queueCount(AgentRabbitTopologyManifest.DEAD_LETTER_QUEUE));
            assertNull(take(AgentRabbitTopologyManifest.DISPATCH_QUEUE, true));
            assertAndRemoveOnlyDlqMessage(expected);
        } finally {
            withChannel(AgentCommandDlqRedriverRabbitIntegrationTest::bindGeneralRoute);
        }
    }

    @Test
    void publishNackSanitizedExceptionAndThrownExceptionAllRequeueSource() throws Exception {
        AgentConfirmedPublishRequest nackRequest = request("nack");
        seedDlq(nackRequest);
        AgentRabbitPublishResult nack = redriver(assertingOutcomePublisher(
                nackRequest, 5_000L, new AgentRabbitPublishResult(
                        AgentRabbitPublishResult.Type.NACK,
                        "NACK", "NOT_RETURNED", null, null, "RABBIT_NACK"), null))
                .redrive(nackRequest, 5_000L, 1);
        assertEquals(AgentRabbitPublishResult.Type.NACK, nack.type());
        assertAndRemoveOnlyDlqMessage(nackRequest);

        AgentConfirmedPublishRequest exceptionRequest = request("exception-result");
        seedDlq(exceptionRequest);
        AgentRabbitPublishResult exception = redriver(assertingOutcomePublisher(
                exceptionRequest, 5_000L, new AgentRabbitPublishResult(
                        AgentRabbitPublishResult.Type.EXCEPTION,
                        "NONE", "NOT_RETURNED", null, null,
                        "RABBIT_PUBLISH_EXCEPTION"), null))
                .redrive(exceptionRequest, 5_000L, 1);
        assertEquals(AgentRabbitPublishResult.Type.EXCEPTION, exception.type());
        assertEquals("RABBIT_PUBLISH_EXCEPTION", exception.errorCode());
        assertAndRemoveOnlyDlqMessage(exceptionRequest);

        AgentConfirmedPublishRequest thrownRequest = request("exception-thrown");
        seedDlq(thrownRequest);
        AgentRabbitPublishResult thrown = redriver(assertingOutcomePublisher(
                thrownRequest, 5_000L, null,
                new IllegalStateException("broker credential detail must not escape")))
                .redrive(thrownRequest, 5_000L, 1);
        assertEquals(AgentRabbitPublishResult.Type.EXCEPTION, thrown.type());
        assertEquals("DLQ_BROKER_FAILURE", thrown.errorCode());
        assertAndRemoveOnlyDlqMessage(thrownRequest);
    }

    @Test
    void confirmTimeoutDoesNotAckAndRequeuesSource() throws Exception {
        AgentConfirmedPublishRequest expected = request("timeout");
        seedDlq(expected);
        AgentRabbitPublishResult timedOut = new AgentRabbitPublishResult(
                AgentRabbitPublishResult.Type.TIMEOUT,
                "TIMEOUT", "NOT_RETURNED", null, null, "RABBIT_CONFIRM_TIMEOUT");

        AgentRabbitPublishResult result = redriver(assertingOutcomePublisher(
                expected, 100L, timedOut, null)).redrive(expected, 100L, 1);

        assertEquals(AgentRabbitPublishResult.Type.TIMEOUT, result.type());
        assertEquals("RABBIT_CONFIRM_TIMEOUT", result.errorCode());
        assertAndRemoveOnlyDlqMessage(expected);
    }

    @Test
    void boundedNonTargetScanRequeuesHeldMessageAndLeavesTargetUntouched() throws Exception {
        AgentConfirmedPublishRequest expected = request("target");
        AgentConfirmedPublishRequest nonTarget = request("non-target");
        seedDlq(nonTarget);
        seedDlq(expected);
        AtomicInteger publishes = new AtomicInteger();
        AgentConfirmedRabbitPublisher mustNotPublish = new AgentConfirmedRabbitPublisher() {
            @Override
            public AgentRabbitPublishResult publish(
                    AgentConfirmedPublishRequest request, long confirmTimeoutMillis) {
                fail("bounded non-target scan must not publish");
                return null;
            }

            @Override
            public AgentRabbitPublishResult publishPreservingHeaders(
                    AgentConfirmedPublishRequest request, Map<String, Object> preservedHeaders,
                    long confirmTimeoutMillis) {
                publishes.incrementAndGet();
                fail("bounded non-target scan must not publish");
                return null;
            }
        };

        AgentRabbitPublishResult result = redriver(mustNotPublish)
                .redrive(expected, 5_000L, 1);

        assertEquals("DLQ_MESSAGE_NOT_FOUND", result.errorCode());
        assertEquals(0, publishes.get());
        assertEquals(2, queueCount(AgentRabbitTopologyManifest.DEAD_LETTER_QUEUE));
        Set<String> remaining = new LinkedHashSet<>();
        GetResponse first = take(AgentRabbitTopologyManifest.DEAD_LETTER_QUEUE, true);
        GetResponse second = take(AgentRabbitTopologyManifest.DEAD_LETTER_QUEUE, true);
        assertNotNull(first);
        assertNotNull(second);
        remaining.add(first.getProps().getMessageId());
        remaining.add(second.getProps().getMessageId());
        assertEquals(Set.of(nonTarget.messageId(), expected.messageId()), remaining);
        assertNull(take(AgentRabbitTopologyManifest.DEAD_LETTER_QUEUE, true));
        assertNull(take(AgentRabbitTopologyManifest.DISPATCH_QUEUE, true));
    }

    private static AgentCommandDlqRedriverImpl redriver(
            AgentConfirmedRabbitPublisher publisher) {
        return new AgentCommandDlqRedriverImpl(connectionFactory, publisher);
    }

    private static AgentConfirmedRabbitPublisher assertingOutcomePublisher(
            AgentConfirmedPublishRequest expected,
            long expectedTimeout,
            AgentRabbitPublishResult outcome,
            RuntimeException thrown) {
        return new AgentConfirmedRabbitPublisher() {
            @Override
            public AgentRabbitPublishResult publish(
                    AgentConfirmedPublishRequest request, long confirmTimeoutMillis) {
                fail("D09 redrive must use the preserving publisher method");
                return null;
            }

            @Override
            public AgentRabbitPublishResult publishPreservingHeaders(
                    AgentConfirmedPublishRequest request,
                    Map<String, Object> preservedHeaders,
                    long confirmTimeoutMillis) {
                assertRequestIdentity(expected, request);
                assertEquals(expectedTimeout, confirmTimeoutMillis);
                assertCanonicalHeadersExactly(
                        AgentCommandAmqpContract.headers(expected), preservedHeaders);
                if (thrown != null) throw thrown;
                return outcome;
            }
        };
    }

    private static Map<String, Object> seedDlq(AgentConfirmedPublishRequest request)
            throws Exception {
        long before = queueCount(AgentRabbitTopologyManifest.DEAD_LETTER_QUEUE);
        Map<String, Object> headers = new LinkedHashMap<>(
                AgentCommandAmqpContract.headers(request));
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .contentType(AgentCommandAmqpContract.CONTENT_TYPE)
                .contentEncoding(AgentCommandAmqpContract.CONTENT_ENCODING)
                .deliveryMode(2)
                .messageId(request.messageId())
                .type(AgentCommandAmqpContract.MESSAGE_TYPE)
                .headers(headers)
                .build();
        withChannel(channel -> {
            channel.confirmSelect();
            channel.basicPublish(
                    AgentRabbitTopologyManifest.DEAD_LETTER_EXCHANGE,
                    AgentRabbitTopologyManifest.DEAD_ROUTING_KEY,
                    true, properties, request.wirePayload());
            channel.waitForConfirmsOrDie(5_000L);
        });
        assertEquals(before + 1,
                queueCount(AgentRabbitTopologyManifest.DEAD_LETTER_QUEUE));
        return Map.copyOf(headers);
    }

    private static void assertAndRemoveOnlyDlqMessage(
            AgentConfirmedPublishRequest expected) throws Exception {
        assertEquals(1, queueCount(AgentRabbitTopologyManifest.DEAD_LETTER_QUEUE));
        GetResponse source = take(AgentRabbitTopologyManifest.DEAD_LETTER_QUEUE, true);
        assertNotNull(source);
        assertEquals(expected.messageId(), source.getProps().getMessageId());
        assertArrayEquals(expected.wirePayload(), source.getBody());
        assertCanonicalHeadersExactly(
                AgentCommandAmqpContract.headers(expected), source.getProps().getHeaders());
        assertEquals(0, queueCount(AgentRabbitTopologyManifest.DEAD_LETTER_QUEUE));
        assertNull(take(AgentRabbitTopologyManifest.DISPATCH_QUEUE, true));
    }

    private static void assertRequestIdentity(
            AgentConfirmedPublishRequest expected, AgentConfirmedPublishRequest actual) {
        assertEquals(expected.destination(), actual.destination());
        assertEquals(expected.routingKey(), actual.routingKey());
        assertArrayEquals(expected.wirePayload(), actual.wirePayload());
        assertArrayEquals(expected.wirePayloadHash(), actual.wirePayloadHash());
        assertEquals(expected.messageId(), actual.messageId());
        assertEquals(expected.eventId(), actual.eventId());
        assertEquals(expected.deliveryId(), actual.deliveryId());
        assertEquals(expected.commandId(), actual.commandId());
        assertEquals(expected.tenantId(), actual.tenantId());
        assertEquals(expected.clientId(), actual.clientId());
        assertEquals(expected.taskId(), actual.taskId());
        assertEquals(expected.targetAgentId(), actual.targetAgentId());
        assertEquals(expected.commandType(), actual.commandType());
        assertEquals(expected.activeAttempt(), actual.activeAttempt());
        assertEquals(expected.expiresAt(), actual.expiresAt());
        assertEquals(expected.topologySha256(), actual.topologySha256());
        assertEquals(expected.sourceSettlementRetry(), actual.sourceSettlementRetry());
    }

    private static void assertCanonicalHeadersExactly(
            Map<String, Object> expected, Map<String, Object> actual) {
        assertCanonicalHeadersPreserved(expected, actual);
        assertEquals(expected.keySet(), actual.keySet());
        assertEquals(13, actual.size());
    }

    private static void assertCanonicalHeadersPreserved(
            Map<String, Object> expected, Map<String, Object> actual) {
        assertNotNull(actual);
        assertEquals(13, expected.size());
        assertTrue(actual.keySet().containsAll(expected.keySet()));
        Set<String> extraHeaders = new LinkedHashSet<>(actual.keySet());
        extraHeaders.removeAll(expected.keySet());
        assertTrue(extraHeaders.isEmpty()
                        || extraHeaders.equals(Set.of("spring_returned_message_correlation")),
                "only Spring's publisher-return correlation header may be transport-added");
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            Object brokerValue = actual.get(entry.getKey());
            if (entry.getValue() instanceof Number number) {
                assertTrue(brokerValue instanceof Number, entry.getKey());
                assertEquals(integral(number), integral((Number) brokerValue), entry.getKey());
            } else {
                assertEquals(entry.getValue(), headerText(brokerValue), entry.getKey());
            }
        }
        for (String extraHeader : extraHeaders) {
            String extraValue = headerText(actual.get(extraHeader));
            assertTrue(extraValue.matches("[0-9a-f-]{36}"), extraHeader);
            assertFalseContainsSensitive(extraValue);
        }
    }

    private static void assertFalseContainsSensitive(String value) {
        assertTrue(!value.contains(username)
                        && !value.contains(password)
                        && !value.contains(virtualHost),
                "transport-added header must not contain broker identity or credentials");
    }

    private static long integral(Number value) {
        assertNotNull(value);
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return value.longValue();
        }
        if (value instanceof BigInteger integer) return integer.longValueExact();
        if (value instanceof BigDecimal decimal) return decimal.longValueExact();
        fail("non-integral broker header number " + value.getClass().getName());
        return 0L;
    }

    private static String headerText(Object value) {
        if (value instanceof String text) return text;
        if (value instanceof LongString text) {
            return new String(text.getBytes(), StandardCharsets.UTF_8);
        }
        fail("non-text broker header " + (value == null ? "null" : value.getClass().getName()));
        return null;
    }

    private static AgentConfirmedPublishRequest request(String label) {
        int sequence = REQUEST_SEQUENCE.incrementAndGet();
        String messageId = UUID.nameUUIDFromBytes(
                (runId + ":" + label + ":" + sequence).getBytes(StandardCharsets.UTF_8))
                .toString();
        String eventId = "evt-" + label + "-" + sequence;
        String commandId = "cmd-" + label + "-" + sequence;
        String taskId = "task-" + label + "-" + sequence;
        byte[] body = ("{\"schemaVersion\":1,\"messageType\":\"command.dispatch\","
                + "\"messageId\":\"" + messageId + "\","
                + "\"commandId\":\"" + commandId + "\","
                + "\"tenantId\":\"tenant-d09\",\"clientId\":\"client-d09\","
                + "\"taskId\":\"" + taskId + "\","
                + "\"targetAgentId\":\"agent-d09\","
                + "\"commandType\":\"TASK_INVITE\",\"attempt\":1,"
                + "\"expiresAt\":4000000000000,"
                + "\"payload\":{\"opaque\":true,\"label\":\"" + label + "\"}}")
                .getBytes(StandardCharsets.UTF_8);
        return new AgentConfirmedPublishRequest(
                AgentRabbitTopologyManifest.MAIN_EXCHANGE,
                AgentRabbitTopologyManifest.GENERAL_ROUTING_KEY,
                body, AgentCommandAmqpContract.sha256(body), messageId, eventId,
                sequence, commandId, "tenant-d09", "client-d09", taskId, "agent-d09",
                "TASK_INVITE", 1, 4_000_000_000_000L, MANIFEST.sha256(), 0);
    }

    private static AgentRabbitSafetyGate allowedGate() {
        AgentRabbitSafetyProperties properties = new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(true),
                new AgentRabbitSafetyProperties.RabbitTopology(true),
                new AgentRabbitSafetyProperties.RabbitPublish(true),
                new AgentRabbitSafetyProperties.RabbitConsume(true),
                new AgentRabbitSafetyProperties.RabbitDispatch(true),
                new AgentRabbitSafetyProperties.RabbitBroker(
                        broker.getHost(), mappedAmqpPort, username, password, virtualHost));
        return new AgentRabbitSafetyGate(properties,
                new AgentRabbitDispatchScopeProperties(List.of(
                        new AgentRabbitDispatchScopeProperties.AllowedScope(
                                "tenant-d09", "client-d09"))));
    }

    private static void assertBrokerIdentityInsideContainer() throws Exception {
        Container.ExecResult authentication = broker.execInContainer(
                "rabbitmqctl", "authenticate_user", username, password);
        assertEquals(0, authentication.getExitCode(),
                authentication.getStdout() + authentication.getStderr());
        Container.ExecResult vhosts = broker.execInContainer(
                "rabbitmqctl", "-q", "list_vhosts", "name");
        assertEquals(0, vhosts.getExitCode(), vhosts.getStdout() + vhosts.getStderr());
        assertEquals(1, Arrays.stream(vhosts.getStdout().split("\\R"))
                .filter(virtualHost::equals).count(), vhosts.getStdout());
    }

    private static void unbindGeneralRoute() throws Exception {
        withChannel(channel -> channel.queueUnbind(
                AgentRabbitTopologyManifest.DISPATCH_QUEUE,
                AgentRabbitTopologyManifest.MAIN_EXCHANGE,
                AgentRabbitTopologyManifest.GENERAL_ROUTING_KEY));
    }

    private static void bindGeneralRoute(Channel channel) throws IOException {
        channel.queueBind(
                AgentRabbitTopologyManifest.DISPATCH_QUEUE,
                AgentRabbitTopologyManifest.MAIN_EXCHANGE,
                AgentRabbitTopologyManifest.GENERAL_ROUTING_KEY);
    }

    private static long queueCount(String queue) throws Exception {
        long[] count = new long[1];
        withChannel(channel -> count[0] = channel.queueDeclarePassive(queue).getMessageCount());
        return count[0];
    }

    private static GetResponse take(String queue, boolean autoAck) throws Exception {
        GetResponse[] response = new GetResponse[1];
        withChannel(channel -> response[0] = channel.basicGet(queue, autoAck));
        return response[0];
    }

    private static void withChannel(ChannelAction action) throws Exception {
        org.springframework.amqp.rabbit.connection.Connection connection = null;
        Channel channel = null;
        try {
            connection = connectionFactory.createConnection();
            channel = connection.createChannel(false);
            action.run(channel);
        } finally {
            if (channel != null) channel.close();
            if (connection != null) connection.close();
        }
    }

    private static void stopOwnedBroker() {
        if (broker != null) broker.stop();
    }

    private static List<String> hostListenerSnapshot(int port) throws IOException {
        String hexadecimalPort = "%04X".formatted(port);
        List<String> listeners = new ArrayList<>();
        for (String table : List.of("tcp", "tcp6")) {
            Path path = Path.of("/proc/net/" + table);
            if (!Files.isRegularFile(path)) {
                throw new IOException("missing host listener source " + path);
            }
            List<String> lines = Files.readAllLines(path, StandardCharsets.US_ASCII);
            for (String line : lines.subList(1, lines.size())) {
                String[] fields = line.trim().split("\\s+");
                if (fields.length > 9 && "0A".equals(fields[3])
                        && fields[1].endsWith(":" + hexadecimalPort)) {
                    listeners.add(table + "|" + fields[1] + "|uid=" + fields[7]
                            + "|inode=" + fields[9]);
                }
            }
        }
        return listeners.stream().sorted().toList();
    }

    private static String sha256(String value) {
        return HexFormat.of().formatHex(AgentCommandAmqpContract.sha256(
                value.getBytes(StandardCharsets.UTF_8)));
    }

    @FunctionalInterface
    private interface ChannelAction {
        void run(Channel channel) throws Exception;
    }
}
