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
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Real-broker D09 gate. It is intentionally focused, non-skippable, and exact-owned. */
class AgentCommandDlqRedriverRabbitIntegrationTest {
    private static final Path RABBITMQ_HOME = Path.of("/home/isp/apps/rabbitmq");
    private static final Path RABBITMQ_SERVER =
            RABBITMQ_HOME.resolve("sbin/rabbitmq-server");
    private static final Path RABBITMQ_VERSION_FILE = RABBITMQ_HOME.resolve("ebin/rabbit.app");
    private static final Path PRODUCTION_MNESIA =
            RABBITMQ_HOME.resolve("var/lib/rabbitmq/mnesia");
    private static final Path PRODUCTION_LOG =
            RABBITMQ_HOME.resolve("var/log/rabbitmq");
    private static final long PRODUCTION_RABBIT_PID = 8150L;
    private static final int DEFAULT_AMQP_PORT = 5672;
    private static final int DEFAULT_DISTRIBUTION_PORT = 25672;
    private static final String FIXTURE_CONTRACT = """
            d09-rabbit-redrive-integration/v3
            runtime=/home/isp/apps/rabbitmq/sbin/rabbitmq-server
            broker=rabbitmq-3.6.11-local-isolated-second-node
            lifecycle=junit-direct-child-exact-pid-start-marker-bounded-term-force
            network=loopback-random-amqp-distribution-never-default
            identity=unique-short-node-cookie-user-password-vhost
            storage=fresh-temp-mnesia-log-config-plugins-pid
            resources=io-thread-pool-4-async-4-schedulers-2
            readiness=90s-child-alive-amqp-listener-authenticated-probe-each-round
            diagnostics=pre-cleanup-console-main-sasl-crash-existence-size-tail-64k-redacted
            plugins=none-amqp-only
            topology=d04-canonical-explicit-provision
            source=basic-get-no-auto-ack
            settle=ack-only-after-confirmed-not-returned
            failure=return-nack-exception-timeout-requeue
            scan=bounded-non-target-requeue
            host-guard=5672-25672-listeners-pid-8150-production-tree-before-after
            cleanup=exact-child-only-owned-temp-root
            """;
    private static final String FIXTURE_SHA256 =
            "46dd3c466a1e04e9c89f54a5365040ec36081387d3348b7f027d765890800991";
    private static final AgentRabbitTopologyManifest MANIFEST =
            AgentRabbitTopologyManifest.canonical();
    private static final AtomicInteger REQUEST_SEQUENCE = new AtomicInteger();

    private static LocalRabbitBroker broker;
    private static CachingConnectionFactory connectionFactory;
    private static AgentRabbitTopologyProvisioner provisioner;
    private static AgentConfirmedRabbitPublisher confirmedPublisher;
    private static String runId;
    private static String username;
    private static String password;
    private static String virtualHost;
    private static int mappedAmqpPort;
    private static HostRabbitState hostStateBefore;

    @BeforeAll
    static void startExactOwnedBroker() throws Exception {
        assertEquals(FIXTURE_SHA256, sha256(FIXTURE_CONTRACT));
        assertEquals("4", LocalRabbitBroker.IO_THREAD_POOL_SIZE);
        assertEquals("+S 2:2", LocalRabbitBroker.LOW_RESOURCE_ERL_ARGS);
        assertEquals("96fd7d32aba468eacbcf96dfa5d441fd938f0d0cb5c6dafcdcae9e797fb4110e",
                MANIFEST.sha256());
        assertInstalledRabbitVersion();
        hostStateBefore = hostRabbitState();

        runId = UUID.randomUUID().toString().replace("-", "");
        username = "d09_" + runId.substring(0, 16);
        password = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        virtualHost = "/d09-" + runId;
        broker = LocalRabbitBroker.create(runId, username, password, virtualHost);

        try {
            broker.startAndAwaitReady();
            mappedAmqpPort = broker.amqpPort();
            assertHostRabbitStateUnchanged();

            connectionFactory = new CachingConnectionFactory("127.0.0.1", mappedAmqpPort);
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
                    "D09_RABBIT_FIXTURE fixtureSha256=%s topologySha256=%s "
                            + "brokerVersion=%s node=%s childPid=%d childStart=%s "
                            + "runId=%s amqpPort=%d distributionPort=%d root=%s "
                            + "ioThreadPool=%s additionalErlArgs=%s "
                            + "vhost=%s user=%s host5672Before=%s host25672Before=%s "
                            + "productionPid=%d productionPidStart=%s "
                            + "productionMnesiaSha256=%s productionLogSha256=%s%n",
                    FIXTURE_SHA256, MANIFEST.sha256(), broker.serverVersion(),
                    broker.nodeName(), broker.childPid(), broker.childStartMarker(), runId,
                    mappedAmqpPort, broker.distributionPort(), broker.root(),
                    LocalRabbitBroker.IO_THREAD_POOL_SIZE,
                    LocalRabbitBroker.LOW_RESOURCE_ERL_ARGS,
                    virtualHost, username, hostStateBefore.listener5672(),
                    hostStateBefore.listener25672(), PRODUCTION_RABBIT_PID,
                    hostStateBefore.productionProcess().startMarker(),
                    hostStateBefore.productionMnesia().sha256(),
                    hostStateBefore.productionLog().sha256());
        } catch (Throwable failure) {
            Throwable cleanupFailure = cleanupOwnedFixture();
            try {
                assertHostRabbitStateUnchanged();
            } catch (Throwable hostFailure) {
                failure.addSuppressed(hostFailure);
            }
            if (cleanupFailure != null) failure.addSuppressed(cleanupFailure);
            throw failure;
        }
    }

    @AfterAll
    static void stopExactOwnedBrokerAndVerifyHostState() throws Exception {
        Throwable cleanupFailure = cleanupOwnedFixture();
        Throwable hostFailure = null;
        HostRabbitState hostStateAfter = null;
        try {
            hostStateAfter = hostRabbitState();
            if (hostStateBefore != null) {
                assertEquals(hostStateBefore, hostStateAfter,
                        "D09 local node must not mutate host Rabbit state");
            }
        } catch (Throwable failure) {
            hostFailure = failure;
        }

        System.out.printf(
                "D09_RABBIT_CLEANUP node=%s childPid=%s root=%s cleaned=%s "
                        + "termination=%s host5672After=%s host25672After=%s "
                        + "productionPid=%d productionPidStart=%s "
                        + "productionMnesiaSha256=%s productionLogSha256=%s%n",
                broker == null ? null : broker.nodeName(),
                broker == null ? null : broker.childPid(),
                broker == null ? null : broker.root(),
                broker == null || broker.isCleaned(),
                broker == null ? "not-created" : broker.terminationMode(),
                hostStateAfter == null ? "unavailable" : hostStateAfter.listener5672(),
                hostStateAfter == null ? "unavailable" : hostStateAfter.listener25672(),
                PRODUCTION_RABBIT_PID,
                hostStateAfter == null ? "unavailable"
                        : hostStateAfter.productionProcess().startMarker(),
                hostStateAfter == null ? "unavailable"
                        : hostStateAfter.productionMnesia().sha256(),
                hostStateAfter == null ? "unavailable"
                        : hostStateAfter.productionLog().sha256());
        if (cleanupFailure != null) {
            if (hostFailure != null) cleanupFailure.addSuppressed(hostFailure);
            throw new AssertionError("D09 exact-owned Rabbit cleanup failed", cleanupFailure);
        }
        if (hostFailure != null) throw new AssertionError(
                "D09 changed host Rabbit state", hostFailure);
    }

    private static Throwable cleanupOwnedFixture() {
        Throwable cleanupFailure = null;
        try {
            if (connectionFactory != null) {
                connectionFactory.destroy();
                connectionFactory = null;
            }
        } catch (Throwable failure) {
            cleanupFailure = failure;
        }
        try {
            if (broker != null) broker.stopExactChildAndDeleteRoot();
        } catch (Throwable failure) {
            if (cleanupFailure == null) cleanupFailure = failure;
            else cleanupFailure.addSuppressed(failure);
        }
        return cleanupFailure;
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
    void isolatedBrokerUsesRandomPortsDedicatedIdentityAndCanonicalTopology() throws Exception {
        assertTrue(broker.isAlive());
        assertEquals("3.6.11", broker.serverVersion());
        assertEquals(broker.childPid(), broker.pidFileValue());
        ProcessIdentity currentChild = processIdentity(broker.childPid());
        assertEquals(broker.childPid(), currentChild.pid());
        assertEquals(broker.childStartMarker(), currentChild.startMarker());
        assertTrue(broker.hasExactCookieFile());
        assertTrue(broker.nodeName().matches("d09_[0-9a-f]{16}@[A-Za-z0-9_-]+"));
        assertNotEquals(DEFAULT_AMQP_PORT, mappedAmqpPort);
        assertNotEquals(DEFAULT_DISTRIBUTION_PORT, broker.distributionPort());
        assertNotEquals(mappedAmqpPort, broker.distributionPort());
        assertNotEquals("/", virtualHost);
        assertTrue(virtualHost.startsWith("/d09-"));
        assertNotEquals("guest", username);
        assertTrue(password.length() >= 64);
        assertTrue(Files.isDirectory(broker.mnesiaDirectory()));
        assertEquals("[].\n", Files.readString(
                broker.enabledPluginsFile(), StandardCharsets.US_ASCII));
        assertTrue(hostListenerSnapshot(mappedAmqpPort).size() >= 1);
        assertTrue(hostListenerSnapshot(broker.distributionPort()).size() >= 1);
        assertHostRabbitStateUnchanged();

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
                        "127.0.0.1", mappedAmqpPort, username, password, virtualHost));
        return new AgentRabbitSafetyGate(properties,
                new AgentRabbitDispatchScopeProperties(List.of(
                        new AgentRabbitDispatchScopeProperties.AllowedScope(
                                "tenant-d09", "client-d09"))));
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

    private static void assertInstalledRabbitVersion() throws IOException {
        assertTrue(Files.isExecutable(RABBITMQ_SERVER),
                "exact RabbitMQ server executable is required");
        String application = Files.readString(RABBITMQ_VERSION_FILE, StandardCharsets.UTF_8);
        assertTrue(application.contains("{vsn, \"3.6.11\"}"),
                "D09 fixture requires exact RabbitMQ 3.6.11");
    }

    private static HostRabbitState hostRabbitState() throws Exception {
        return new HostRabbitState(
                hostListenerSnapshot(DEFAULT_AMQP_PORT),
                hostListenerSnapshot(DEFAULT_DISTRIBUTION_PORT),
                processIdentity(PRODUCTION_RABBIT_PID),
                treeSnapshot(PRODUCTION_MNESIA),
                treeSnapshot(PRODUCTION_LOG));
    }

    private static void assertHostRabbitStateUnchanged() throws Exception {
        if (hostStateBefore != null) {
            assertEquals(hostStateBefore, hostRabbitState(),
                    "D09 local node must not mutate host Rabbit listeners, process, mnesia, or logs");
        }
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

    private static ProcessIdentity processIdentity(long pid) throws IOException {
        Path processRoot = Path.of("/proc", Long.toString(pid));
        String stat = Files.readString(processRoot.resolve("stat"), StandardCharsets.US_ASCII);
        int commandEnd = stat.lastIndexOf(')');
        if (commandEnd < 0 || commandEnd + 2 >= stat.length()) {
            throw new IOException("invalid /proc stat for pid " + pid);
        }
        String[] fieldsFromState = stat.substring(commandEnd + 2).trim().split("\\s+");
        if (fieldsFromState.length <= 19) {
            throw new IOException("missing process start marker for pid " + pid);
        }
        String commandLine = HexFormat.of().formatHex(
                Files.readAllBytes(processRoot.resolve("cmdline")));
        String executable = Files.readSymbolicLink(processRoot.resolve("exe")).toString();
        return new ProcessIdentity(pid, fieldsFromState[19], commandLine, executable);
    }

    private static TreeSnapshot treeSnapshot(Path root) throws Exception {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(absoluteRoot, LinkOption.NOFOLLOW_LINKS),
                "required production Rabbit path missing: " + absoluteRoot);
        MessageDigest digest = sha256Digest();
        long entries = 0L;
        long bytes = 0L;
        try (Stream<Path> paths = Files.walk(absoluteRoot)) {
            for (Path path : paths.sorted().toList()) {
                entries++;
                Path relative = absoluteRoot.relativize(path);
                updateDigest(digest, relative.toString());
                BasicFileAttributes attributes = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isRegularFile()) {
                    digest.update((byte) 'F');
                    bytes += attributes.size();
                    try (var input = Files.newInputStream(path)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = input.read(buffer)) >= 0) {
                            if (read > 0) digest.update(buffer, 0, read);
                        }
                    }
                } else if (attributes.isSymbolicLink()) {
                    digest.update((byte) 'L');
                    updateDigest(digest, Files.readSymbolicLink(path).toString());
                } else if (attributes.isDirectory()) {
                    digest.update((byte) 'D');
                } else {
                    digest.update((byte) 'O');
                }
                digest.update((byte) 0);
            }
        }
        return new TreeSnapshot(absoluteRoot.toString(), HexFormat.of().formatHex(digest.digest()),
                entries, bytes);
    }

    private static void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private record HostRabbitState(
            List<String> listener5672,
            List<String> listener25672,
            ProcessIdentity productionProcess,
            TreeSnapshot productionMnesia,
            TreeSnapshot productionLog) {
    }

    private record ProcessIdentity(
            long pid, String startMarker, String commandLineHex, String executable) {
    }

    private record TreeSnapshot(String root, String sha256, long entries, long bytes) {
    }

    private record ReservedPorts(int amqp, int distribution) {
        private static ReservedPorts select() throws IOException {
            try (ServerSocket amqp = reservePort(-1);
                    ServerSocket distribution = reservePort(amqp.getLocalPort())) {
                return new ReservedPorts(amqp.getLocalPort(), distribution.getLocalPort());
            }
        }

        private static ServerSocket reservePort(int excluded) throws IOException {
            for (int attempt = 0; attempt < 32; attempt++) {
                ServerSocket socket = new ServerSocket();
                socket.setReuseAddress(false);
                socket.bind(new InetSocketAddress("127.0.0.1", 0));
                int port = socket.getLocalPort();
                if (port != excluded && port != DEFAULT_AMQP_PORT
                        && port != DEFAULT_DISTRIBUTION_PORT) {
                    return socket;
                }
                socket.close();
            }
            throw new IOException("unable to reserve non-default Rabbit fixture port");
        }
    }

    private static final class LocalRabbitBroker {
        private static final long START_TIMEOUT_MILLIS = 90_000L;
        private static final long TERM_TIMEOUT_MILLIS = 20_000L;
        private static final long FORCE_TIMEOUT_MILLIS = 10_000L;
        private static final int DIAGNOSTIC_TAIL_LIMIT_BYTES = 64 * 1024;
        private static final String IO_THREAD_POOL_SIZE = "4";
        private static final String LOW_RESOURCE_ERL_ARGS =
                "+S 2:2";

        private final String runId;
        private final String username;
        private final String password;
        private final String virtualHost;
        private final String nodeName;
        private final String cookie;
        private final Path root;
        private final Path ownershipMarker;
        private final Path mnesiaBase;
        private final Path mnesiaDirectory;
        private final Path logBase;
        private final Path configBase;
        private final Path enabledPluginsFile;
        private final Path pidFile;
        private final Path consoleLog;
        private final Path mainLog;
        private final Path saslLog;
        private final Path erlCrashDump;
        private final int amqpPort;
        private final int distributionPort;

        private Process process;
        private String childStartMarker;
        private String serverVersion;
        private String terminationMode = "not-stopped";
        private boolean cleaned;

        private LocalRabbitBroker(
                String runId,
                String username,
                String password,
                String virtualHost,
                String nodeName,
                String cookie,
                Path root,
                ReservedPorts ports) {
            this.runId = runId;
            this.username = username;
            this.password = password;
            this.virtualHost = virtualHost;
            this.nodeName = nodeName;
            this.cookie = cookie;
            this.root = root;
            this.ownershipMarker = root.resolve("D09-OWNED");
            this.mnesiaBase = root.resolve("mnesia");
            this.mnesiaDirectory = mnesiaBase.resolve(nodeName);
            this.logBase = root.resolve("log");
            this.configBase = root.resolve("config/rabbitmq");
            this.enabledPluginsFile = root.resolve("config/enabled_plugins");
            this.pidFile = root.resolve("run/rabbit.pid");
            this.consoleLog = logBase.resolve("console.log");
            this.mainLog = logBase.resolve(nodeName + ".log");
            this.saslLog = logBase.resolve(nodeName + "-sasl.log");
            this.erlCrashDump = logBase.resolve("erl_crash.dump");
            this.amqpPort = ports.amqp();
            this.distributionPort = ports.distribution();
        }

        static LocalRabbitBroker create(
                String runId, String username, String password, String virtualHost)
                throws Exception {
            ReservedPorts ports = ReservedPorts.select();
            String host = Files.readString(Path.of("/etc/hostname"), StandardCharsets.US_ASCII)
                    .trim().split("\\.", 2)[0];
            assertTrue(host.matches("[A-Za-z0-9_-]+"), "short hostname is required");
            String nodeName = "d09_" + runId.substring(0, 16) + "@" + host;
            String cookie = "D09" + UUID.randomUUID().toString().replace("-", "")
                    + runId.substring(0, 16);
            Path root = Files.createTempDirectory("cyf-d09-rabbit-_")
                    .toAbsolutePath().normalize();
            try {
                Files.setPosixFilePermissions(root,
                        PosixFilePermissions.fromString("rwx------"));
                Files.writeString(root.resolve("D09-OWNED"), runId + "\n",
                        StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                return new LocalRabbitBroker(runId, username, password, virtualHost,
                        nodeName, cookie, root, ports);
            } catch (IOException | RuntimeException failure) {
                try (Stream<Path> paths = Files.walk(root)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(path);
                    }
                } catch (Throwable cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        void startAndAwaitReady() throws Exception {
            prepareOwnedRoot();
            ProcessBuilder builder = new ProcessBuilder(
                    RABBITMQ_SERVER.toString(), "-noinput");
            builder.directory(root.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(consoleLog.toFile());
            Map<String, String> environment = builder.environment();
            environment.clear();
            environment.put("PATH", System.getenv().getOrDefault(
                    "PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"));
            environment.put("LANG", "C");
            environment.put("LC_ALL", "C");
            environment.put("HOME", root.resolve("home").toString());
            environment.put("TMPDIR", root.resolve("tmp").toString());
            environment.put("HOSTNAME", nodeName.substring(nodeName.indexOf('@') + 1));
            environment.put("RABBITMQ_ALLOW_INPUT", "true");
            environment.put("RABBITMQ_USE_LONGNAME", "false");
            environment.put("RABBITMQ_NODENAME", nodeName);
            environment.put("RABBITMQ_NODE_IP_ADDRESS", "127.0.0.1");
            environment.put("RABBITMQ_NODE_PORT", Integer.toString(amqpPort));
            environment.put("RABBITMQ_DIST_PORT", Integer.toString(distributionPort));
            environment.put("RABBITMQ_IO_THREAD_POOL_SIZE", IO_THREAD_POOL_SIZE);
            environment.put("RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS", LOW_RESOURCE_ERL_ARGS);
            environment.put("RABBITMQ_MNESIA_BASE", mnesiaBase.toString());
            environment.put("RABBITMQ_MNESIA_DIR", mnesiaDirectory.toString());
            environment.put("RABBITMQ_LOG_BASE", logBase.toString());
            environment.put("RABBITMQ_LOGS", mainLog.toString());
            environment.put("RABBITMQ_SASL_LOGS", saslLog.toString());
            environment.put("RABBITMQ_CONFIG_FILE", configBase.toString());
            environment.put("RABBITMQ_CONF_ENV_FILE",
                    root.resolve("config/rabbitmq-env.conf").toString());
            environment.put("RABBITMQ_ENABLED_PLUGINS_FILE", enabledPluginsFile.toString());
            environment.put("RABBITMQ_PLUGINS_DIR", RABBITMQ_HOME.resolve("plugins").toString());
            environment.put("RABBITMQ_PLUGINS_EXPAND_DIR",
                    root.resolve("plugins-expand").toString());
            environment.put("RABBITMQ_PID_FILE", pidFile.toString());
            environment.put("ERL_CRASH_DUMP", erlCrashDump.toString());
            process = builder.start();
            ProcessIdentity initialIdentity = processIdentity(process.pid());
            childStartMarker = initialIdentity.startMarker();
            awaitAuthenticatedAmqp();
            ProcessIdentity readyIdentity = processIdentity(process.pid());
            assertEquals(initialIdentity.pid(), readyIdentity.pid(),
                    "direct child PID changed during Rabbit startup");
            assertEquals(childStartMarker, readyIdentity.startMarker(),
                    "direct child start marker changed during Rabbit startup");
            assertEquals(process.pid(), pidFileValue(),
                    "isolated Rabbit PID file must identify the direct child");
        }

        private void prepareOwnedRoot() throws IOException {
            for (Path path : List.of(mnesiaBase, mnesiaDirectory, logBase, configBase,
                    enabledPluginsFile, pidFile, consoleLog, mainLog, saslLog, erlCrashDump)) {
                assertTrue(path.toAbsolutePath().normalize().startsWith(root),
                        "isolated Rabbit path escaped owned root: " + path);
                assertTrue(!path.startsWith(PRODUCTION_MNESIA) && !path.startsWith(PRODUCTION_LOG),
                        "isolated Rabbit path overlaps production state: " + path);
            }
            Files.createDirectories(root.resolve("home"));
            Files.createDirectories(root.resolve("tmp"));
            Files.createDirectories(mnesiaBase);
            Files.createDirectories(logBase);
            Files.createDirectories(configBase.getParent());
            Files.createDirectories(pidFile.getParent());
            assertEquals(runId + "\n", Files.readString(
                    ownershipMarker, StandardCharsets.US_ASCII));
            Path cookieFile = root.resolve("home/.erlang.cookie");
            Files.writeString(cookieFile, cookie, StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            cookieFile.toFile().setReadable(false, false);
            cookieFile.toFile().setWritable(false, false);
            assertTrue(cookieFile.toFile().setReadable(true, true));
            assertTrue(cookieFile.toFile().setWritable(true, true));
            Files.setPosixFilePermissions(cookieFile, PosixFilePermissions.fromString("rw-------"));
            Path environmentFile = root.resolve("config/rabbitmq-env.conf");
            Files.writeString(environmentFile, "# D09 isolated\n",
                    StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
            Files.writeString(enabledPluginsFile, "[].\n", StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE_NEW);
            Path configFile = configBase.resolveSibling("rabbitmq.config");
            Files.writeString(configFile, configText(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            Files.setPosixFilePermissions(environmentFile,
                    PosixFilePermissions.fromString("rw-------"));
            Files.setPosixFilePermissions(enabledPluginsFile,
                    PosixFilePermissions.fromString("rw-------"));
            Files.setPosixFilePermissions(configFile,
                    PosixFilePermissions.fromString("rw-------"));
        }

        private String configText() {
            return "[\n"
                    + " {rabbit, [\n"
                    + "   {tcp_listeners, [{\"127.0.0.1\", " + amqpPort + "}]},\n"
                    + "   {default_user, <<\"" + username + "\">>},\n"
                    + "   {default_pass, <<\"" + password + "\">>},\n"
                    + "   {default_vhost, <<\"" + virtualHost + "\">>},\n"
                    + "   {loopback_users, []}\n"
                    + " ]}\n"
                    + "].\n";
        }

        private void awaitAuthenticatedAmqp() throws Exception {
            long deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(START_TIMEOUT_MILLIS);
            Throwable lastFailure = null;
            boolean listenerObserved = false;
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    throw readinessFailure(
                            "child exited before readiness", lastFailure, listenerObserved);
                }
                try {
                    listenerObserved = !hostListenerSnapshot(amqpPort).isEmpty();
                } catch (Throwable failure) {
                    throw readinessFailure(
                            "AMQP listener inspection failed", failure, listenerObserved);
                }
                if (listenerObserved) {
                    try {
                        ConnectionFactory probe = new ConnectionFactory();
                        probe.setHost("127.0.0.1");
                        probe.setPort(amqpPort);
                        probe.setUsername(username);
                        probe.setPassword(password);
                        probe.setVirtualHost(virtualHost);
                        probe.setConnectionTimeout(1_000);
                        probe.setHandshakeTimeout(2_000);
                        probe.setAutomaticRecoveryEnabled(false);
                        try (Connection connection = probe.newConnection(
                                "D09-" + runId.substring(0, 16) + "-readiness")) {
                            Object version = connection.getServerProperties().get("version");
                            serverVersion = String.valueOf(version);
                            if (!"3.6.11".equals(serverVersion)) {
                                throw readinessFailure(
                                        "unexpected broker version " + serverVersion, null, true);
                            }
                            return;
                        }
                    } catch (IOException | TimeoutException failure) {
                        lastFailure = failure;
                    }
                }
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw readinessFailure(
                            "readiness wait interrupted", failure, listenerObserved);
                }
            }
            throw readinessFailure("readiness timed out after " + START_TIMEOUT_MILLIS + "ms",
                    lastFailure, listenerObserved);
        }

        void stopExactChildAndDeleteRoot() throws Exception {
            if (cleaned) return;
            Throwable stopFailure = null;
            try {
                stopExactChild();
            } catch (Throwable failure) {
                stopFailure = failure;
            }
            if (stopFailure == null || process == null || !process.isAlive()) {
                try {
                    deleteOwnedRoot();
                    cleaned = true;
                } catch (Throwable failure) {
                    if (stopFailure == null) stopFailure = failure;
                    else stopFailure.addSuppressed(failure);
                }
            }
            if (stopFailure != null) throw new AssertionError(
                    "isolated Rabbit exact-child cleanup failed", stopFailure);
        }

        private void stopExactChild() throws Exception {
            if (process == null) {
                terminationMode = "not-started";
                return;
            }
            if (!process.isAlive()) {
                terminationMode = "already-exited";
                return;
            }
            assertExactChildStartMarker();
            process.destroy();
            terminationMode = "term";
            if (!process.waitFor(TERM_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                assertExactChildStartMarker();
                process.destroyForcibly();
                terminationMode = "force";
                assertTrue(process.waitFor(FORCE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                        "exact Rabbit child did not stop after bounded force");
            }
        }

        private void assertExactChildStartMarker() throws IOException {
            ProcessIdentity current = processIdentity(process.pid());
            assertEquals(process.pid(), current.pid(),
                    "refusing to terminate a non-owned PID");
            if (childStartMarker != null) {
                assertEquals(childStartMarker, current.startMarker(),
                        "refusing to terminate a reused PID");
            }
        }

        private void deleteOwnedRoot() throws Exception {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
            Path systemTemp = Path.of(System.getProperty("java.io.tmpdir"))
                    .toAbsolutePath().normalize();
            assertEquals(systemTemp, root.getParent(),
                    "refusing to clean outside the JVM temp directory");
            assertTrue(root.getFileName().toString().startsWith("cyf-d09-rabbit-_")
                            && !Files.isSymbolicLink(root),
                    "refusing to clean a non-owned Rabbit root");
            assertEquals(runId + "\n", Files.readString(
                    ownershipMarker, StandardCharsets.US_ASCII));
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
            assertTrue(Files.notExists(root, LinkOption.NOFOLLOW_LINKS),
                    "isolated Rabbit temp root must be removed");
        }

        private AssertionError readinessFailure(
                String reason, Throwable cause, boolean listenerObserved) {
            RabbitDiagnosticCapture diagnostics = captureRabbitDiagnostics();
            Throwable redactedCause = cause == null ? null : new IOException(
                    "readiness probe " + cause.getClass().getSimpleName() + ": "
                            + redact(String.valueOf(cause.getMessage())));
            String message = "isolated Rabbit readiness failed: reason=" + redact(reason)
                    + " childAlive=" + isAlive()
                    + " amqpListener=" + listenerObserved
                    + "\n" + diagnostics.report();
            AssertionError failure = redactedCause == null
                    ? new AssertionError(message)
                    : new AssertionError(message, redactedCause);
            diagnostics.failures().forEach(failure::addSuppressed);
            return failure;
        }

        private RabbitDiagnosticCapture captureRabbitDiagnostics() {
            StringBuilder report = new StringBuilder(
                    "rabbitDiagnostics tailLimitBytes=" + DIAGNOSTIC_TAIL_LIMIT_BYTES);
            List<Throwable> failures = new ArrayList<>();
            for (RabbitDiagnosticFile diagnostic : List.of(
                    new RabbitDiagnosticFile("console", consoleLog),
                    new RabbitDiagnosticFile("main", mainLog),
                    new RabbitDiagnosticFile("sasl", saslLog),
                    new RabbitDiagnosticFile("erl_crash_dump", erlCrashDump))) {
                appendRabbitDiagnostic(report, failures, diagnostic);
            }
            return new RabbitDiagnosticCapture(redact(report.toString()), List.copyOf(failures));
        }

        private void appendRabbitDiagnostic(
                StringBuilder report,
                List<Throwable> failures,
                RabbitDiagnosticFile diagnostic) {
            report.append("\n[").append(diagnostic.label()).append("] ");
            Path path = diagnostic.path();
            try {
                if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
                    report.append("exists=false size=0 tailBytes=0 tail=<absent>");
                    return;
                }
                BasicFileAttributes attributes = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                report.append("exists=true size=").append(attributes.size());
                if (!attributes.isRegularFile()) {
                    report.append(" tailBytes=0 tail=<non-regular>");
                    return;
                }
                int requested = (int) Math.min(
                        attributes.size(), DIAGNOSTIC_TAIL_LIMIT_BYTES);
                byte[] tail = new byte[requested];
                int readBytes;
                try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                    channel.position(Math.max(0L, attributes.size() - requested));
                    ByteBuffer buffer = ByteBuffer.wrap(tail);
                    while (buffer.hasRemaining()) {
                        int read = channel.read(buffer);
                        if (read <= 0) break;
                    }
                    readBytes = buffer.position();
                }
                report.append(" tailBytes=").append(readBytes).append(" tail=<<<")
                        .append(new String(tail, 0, readBytes, StandardCharsets.UTF_8))
                        .append(">>>");
            } catch (Throwable failure) {
                report.append("exists=unknown size=unknown tailBytes=0 tail=<unreadable:")
                        .append(failure.getClass().getSimpleName()).append('>');
                failures.add(new IOException(
                        "Rabbit diagnostic " + diagnostic.label() + " read failed: "
                                + failure.getClass().getName()));
            }
        }

        private String redact(String value) {
            String redacted = value;
            redacted = replaceSensitive(redacted, password, "<redacted-password>");
            redacted = replaceSensitive(redacted, cookie, "<redacted-cookie>");
            redacted = replaceSensitive(redacted, username, "<redacted-user>");
            redacted = replaceSensitive(redacted, virtualHost, "<redacted-vhost>");
            redacted = replaceSensitive(redacted, nodeName, "<redacted-node>");
            redacted = replaceSensitive(redacted, root.toString(), "<redacted-root>");
            redacted = replaceSensitive(redacted, runId, "<redacted-run-id>");
            redacted = replaceSensitive(
                    redacted, runId.substring(0, 16), "<redacted-run-id-prefix>");
            return redacted;
        }

        private static String replaceSensitive(
                String value, String sensitive, String replacement) {
            return sensitive == null || sensitive.isEmpty()
                    ? value
                    : value.replace(sensitive, replacement);
        }

        long pidFileValue() throws IOException {
            return Long.parseLong(Files.readString(pidFile, StandardCharsets.US_ASCII).trim());
        }

        boolean isAlive() {
            return process != null && process.isAlive();
        }

        boolean isCleaned() {
            return cleaned;
        }

        String terminationMode() {
            return terminationMode;
        }

        long childPid() {
            return process == null ? -1L : process.pid();
        }

        String childStartMarker() {
            return childStartMarker;
        }

        boolean hasExactCookieFile() throws IOException {
            Path cookieFile = root.resolve("home/.erlang.cookie");
            return cookie.equals(Files.readString(cookieFile, StandardCharsets.US_ASCII))
                    && Files.getPosixFilePermissions(cookieFile).equals(
                            PosixFilePermissions.fromString("rw-------"));
        }

        String serverVersion() {
            return serverVersion;
        }

        String nodeName() {
            return nodeName;
        }

        Path root() {
            return root;
        }

        Path mnesiaDirectory() {
            return mnesiaDirectory;
        }

        Path enabledPluginsFile() {
            return enabledPluginsFile;
        }

        int amqpPort() {
            return amqpPort;
        }

        int distributionPort() {
            return distributionPort;
        }

        private record RabbitDiagnosticFile(String label, Path path) {
        }

        private record RabbitDiagnosticCapture(String report, List<Throwable> failures) {
        }
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
