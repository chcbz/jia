package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRabbitTopologyManifestTest {
    private final AgentRabbitTopologyManifest manifest =
            AgentRabbitTopologyManifest.canonical();

    @Test
    void canonicalManifestHasExactTwoExchangesFiveQueuesAndEightBindings() {
        assertEquals(2, manifest.exchanges().size());
        assertEquals(5, manifest.queues().size());
        assertEquals(8, manifest.bindings().size());

        assertEquals(List.of(
                        AgentRabbitTopologyManifest.MAIN_EXCHANGE,
                        AgentRabbitTopologyManifest.DEAD_LETTER_EXCHANGE),
                manifest.exchanges().stream()
                        .map(AgentRabbitTopologyManifest.ExchangeSpec::name).toList());
        assertEquals(List.of(
                        AgentRabbitTopologyManifest.DISPATCH_QUEUE,
                        AgentRabbitTopologyManifest.RETRY_5S_QUEUE,
                        AgentRabbitTopologyManifest.RETRY_30S_QUEUE,
                        AgentRabbitTopologyManifest.RETRY_5M_QUEUE,
                        AgentRabbitTopologyManifest.DEAD_LETTER_QUEUE),
                manifest.queues().stream()
                        .map(AgentRabbitTopologyManifest.QueueSpec::name).toList());
    }

    @Test
    void exchangeAndQueueDurabilityAndArgumentsAreExact() {
        List<Exchange> exchanges = manifest.exchangeDeclarations();
        assertTrue(exchanges.get(0) instanceof TopicExchange);
        assertTrue(exchanges.get(1) instanceof DirectExchange);
        for (Exchange exchange : exchanges) {
            assertTrue(exchange.isDurable());
            assertFalse(exchange.isAutoDelete());
            assertFalse(exchange.isInternal());
            assertTrue(exchange.getArguments().isEmpty());
        }

        List<Queue> queues = manifest.queueDeclarations();
        for (Queue queue : queues) {
            assertTrue(queue.isDurable(), queue.getName());
            assertFalse(queue.isExclusive(), queue.getName());
            assertFalse(queue.isAutoDelete(), queue.getName());
        }
        assertEquals(Map.of(
                        "x-dead-letter-exchange", AgentRabbitTopologyManifest.DEAD_LETTER_EXCHANGE,
                        "x-dead-letter-routing-key", AgentRabbitTopologyManifest.DEAD_ROUTING_KEY),
                queues.get(0).getArguments());
        assertRetry(queues.get(1), 5_000L);
        assertRetry(queues.get(2), 30_000L);
        assertRetry(queues.get(3), 300_000L);
        assertTrue(queues.get(4).getArguments().isEmpty());
    }

    @Test
    void routingIsExactWithoutWildcardOrPluginArguments() {
        assertEquals(List.of(
                        AgentRabbitTopologyManifest.GENERAL_ROUTING_KEY,
                        AgentRabbitTopologyManifest.CODING_ROUTING_KEY,
                        AgentRabbitTopologyManifest.RESEARCH_ROUTING_KEY,
                        AgentRabbitTopologyManifest.REVIEW_ROUTING_KEY,
                        AgentRabbitTopologyManifest.RETRY_5S_ROUTING_KEY,
                        AgentRabbitTopologyManifest.RETRY_30S_ROUTING_KEY,
                        AgentRabbitTopologyManifest.RETRY_5M_ROUTING_KEY,
                        AgentRabbitTopologyManifest.DEAD_ROUTING_KEY),
                manifest.bindings().stream()
                        .map(AgentRabbitTopologyManifest.BindingSpec::routingKey).toList());
        assertEquals(Set.of(AgentRabbitTopologyManifest.DISPATCH_QUEUE),
                manifest.bindings().subList(0, 4).stream()
                        .map(AgentRabbitTopologyManifest.BindingSpec::queue)
                        .collect(java.util.stream.Collectors.toSet()));

        for (AgentRabbitTopologyManifest.BindingSpec binding : manifest.bindings()) {
            assertFalse(binding.routingKey().contains("*"), binding.routingKey());
            assertFalse(binding.routingKey().contains("#"), binding.routingKey());
            assertTrue(binding.arguments().isEmpty());
        }
        for (AgentRabbitTopologyManifest.QueueSpec queue : manifest.queues()) {
            assertFalse(queue.arguments().containsKey("x-queue-type"), queue.name());
            assertTrue(queue.arguments().keySet().stream()
                    .noneMatch(key -> key.contains("delayed")), queue.name());
        }
        for (AgentRabbitTopologyManifest.ExchangeSpec exchange : manifest.exchanges()) {
            assertTrue(exchange.arguments().keySet().stream()
                    .noneMatch(key -> key.contains("delayed")), exchange.name());
        }
    }


    @Test
    void publishAllowlistsAreDerivedOnlyFromCanonicalBindings() {
        assertEquals(4, manifest.commandPublishRoutes().size());
        assertEquals(new AgentRabbitTopologyManifest.PublishRoute(
                        AgentRabbitTopologyManifest.MAIN_EXCHANGE,
                        AgentRabbitTopologyManifest.GENERAL_ROUTING_KEY),
                manifest.defaultCommandPublishRoute());
        assertTrue(manifest.allowsCommandPublish(
                AgentRabbitTopologyManifest.MAIN_EXCHANGE,
                AgentRabbitTopologyManifest.CODING_ROUTING_KEY));
        assertFalse(manifest.allowsCommandPublish(
                AgentRabbitTopologyManifest.DEAD_LETTER_EXCHANGE,
                AgentRabbitTopologyManifest.RETRY_5S_ROUTING_KEY));
        assertTrue(manifest.allowsPublish(
                AgentRabbitTopologyManifest.DEAD_LETTER_EXCHANGE,
                AgentRabbitTopologyManifest.RETRY_5S_ROUTING_KEY));
        assertFalse(manifest.allowsPublish(
                AgentRabbitTopologyManifest.MAIN_EXCHANGE, "agent.command.*"));
    }

    @Test
    void declarationsPreserveCanonicalOrderAndIdentity() {
        List<Binding> bindings = manifest.bindingDeclarations();
        for (int index = 0; index < bindings.size(); index++) {
            AgentRabbitTopologyManifest.BindingSpec expected = manifest.bindings().get(index);
            Binding actual = bindings.get(index);
            assertEquals(expected.exchange(), actual.getExchange());
            assertEquals(expected.queue(), actual.getDestination());
            assertEquals(expected.routingKey(), actual.getRoutingKey());
            assertEquals(Binding.DestinationType.QUEUE, actual.getDestinationType());
        }
    }

    @Test
    void manifestIsImmutableDefensiveAndHasStableSha256() throws Exception {
        assertEquals(AgentRabbitTopologyManifest.CANONICAL_SHA256, manifest.sha256());
        assertEquals(manifest.sha256(), HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(manifest.canonicalBytes())));
        assertArrayEquals(manifest.canonicalText().getBytes(StandardCharsets.UTF_8),
                manifest.canonicalBytes());

        byte[] first = manifest.canonicalBytes();
        byte[] second = manifest.canonicalBytes();
        assertNotSame(first, second);
        first[0] = 0;
        assertEquals('a', manifest.canonicalBytes()[0]);

        assertThrows(UnsupportedOperationException.class,
                () -> manifest.exchanges().add(manifest.exchanges().get(0)));
        assertThrows(UnsupportedOperationException.class,
                () -> manifest.queues().get(0).arguments().put("x-queue-type", "quorum"));
        assertTrue(java.util.Arrays.stream(AgentRabbitTopologyManifest.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .allMatch(field -> Modifier.isFinal(field.getModifiers())));
    }

    private static void assertRetry(Queue queue, long ttlMillis) {
        assertEquals(Map.of(
                        "x-message-ttl", ttlMillis,
                        "x-dead-letter-exchange", AgentRabbitTopologyManifest.MAIN_EXCHANGE,
                        "x-dead-letter-routing-key", AgentRabbitTopologyManifest.GENERAL_ROUTING_KEY),
                queue.getArguments());
    }
}
