package cn.jia.agent.cache;

import cn.jia.agent.entity.AgentPersonaEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentPersonaCatalogCacheTest {
    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void sharedEntrySchemaContainsOnlyFrozenImmutableMetadataAllowlist() {
        java.util.Set<String> fields = java.util.Arrays.stream(
                        AgentPersonaCatalogCache.CatalogEntry.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        assertEquals(java.util.Set.of(
                "personaCode", "rankNo", "starName", "name", "title", "avatar",
                "visualConfig", "abilities", "power", "intelligence", "leadership",
                "systemAgent"), fields);
    }

    @Test
    void exactTenantClientAndGenerationProduceIndependentSnapshots() {
        AtomicLong clock = new AtomicLong(1_000L);
        AgentPersonaCatalogCache cache = new AgentPersonaCatalogCache(
                clock::get, Duration.ofMinutes(1).toNanos());
        AtomicInteger tenantALoads = new AtomicInteger();

        AgentPersonaCatalogCache.CatalogSnapshot first = cache.get("Tenant-A", "Client-A", () -> {
            tenantALoads.incrementAndGet();
            return List.of(persona("Tenant-A", "Client-A", "wuyong", "吴用"));
        });
        AgentPersonaCatalogCache.CatalogSnapshot cached = cache.get(
                "Tenant-A", "Client-A", () -> {
                    throw new AssertionError("exact scope should hit cache");
                });
        AgentPersonaCatalogCache.CatalogSnapshot tenantCase = cache.get(
                "tenant-a", "Client-A",
                () -> List.of(persona("tenant-a", "Client-A", "linchong", "林冲")));
        AgentPersonaCatalogCache.CatalogSnapshot clientCase = cache.get(
                "Tenant-A", "client-a",
                () -> List.of(persona("Tenant-A", "client-a", "luzhishen", "鲁智深")));

        assertSame(first, cached);
        assertEquals(1, tenantALoads.get());
        assertNotEquals(first.catalogVersion(), tenantCase.catalogVersion());
        assertNotEquals(first.catalogVersion(), clientCase.catalogVersion());

        cache.invalidateAllAfterCommit();
        AgentPersonaCatalogCache.CatalogSnapshot reloaded = cache.get(
                "Tenant-A", "Client-A", () -> {
                    tenantALoads.incrementAndGet();
                    return List.of(persona("Tenant-A", "Client-A", "wuyong", "吴用"));
                });
        assertEquals(2, tenantALoads.get());
        assertNotEquals(first.catalogVersion(), reloaded.catalogVersion());
    }

    @Test
    void nullEmptyMalformedAndExceptionalLoadsFailClosedAndRemainRetryable() {
        AgentPersonaCatalogCache cache = new AgentPersonaCatalogCache();
        assertThrows(IllegalStateException.class,
                () -> cache.get("Tenant-A", "Client-A", () -> null));
        assertThrows(IllegalStateException.class,
                () -> cache.get("Tenant-A", "Client-A", () -> List.of()));
        assertThrows(IllegalStateException.class,
                () -> cache.get("Tenant-A", "Client-A", () -> {
                    java.util.ArrayList<AgentPersonaEntity> rows = new java.util.ArrayList<>();
                    rows.add(null);
                    return rows;
                }));
        assertThrows(IllegalStateException.class,
                () -> cache.get("Tenant-A", "Client-A",
                        () -> List.of(persona("Tenant-B", "Client-A", "wuyong", "吴用"))));

        AtomicInteger attempts = new AtomicInteger();
        assertThrows(IllegalArgumentException.class,
                () -> cache.get("Tenant-A", "Client-A", () -> {
                    attempts.incrementAndGet();
                    throw new IllegalArgumentException("database unavailable");
                }));
        AgentPersonaCatalogCache.CatalogSnapshot recovered = cache.get(
                "Tenant-A", "Client-A", () -> {
                    attempts.incrementAndGet();
                    return List.of(persona("Tenant-A", "Client-A", "wuyong", "吴用"));
                });
        assertEquals(2, attempts.get());
        assertEquals("wuyong", recovered.entries().getFirst().personaCode());
        assertThrows(UnsupportedOperationException.class,
                () -> recovered.entries().add(recovered.entries().getFirst()));
    }

    @Test
    void concurrentSameScopeMissLoadsExactlyOnce() throws Exception {
        AgentPersonaCatalogCache cache = new AgentPersonaCatalogCache();
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<AgentPersonaCatalogCache.CatalogSnapshot>> futures =
                    new java.util.ArrayList<>();
            for (int index = 0; index < 8; index++) {
                futures.add(executor.submit(() -> cache.get(
                        "Tenant-A", "Client-A", () -> {
                            loads.incrementAndGet();
                            loaderEntered.countDown();
                            try {
                                if (!releaseLoader.await(5, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("loader release timed out");
                                }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(interrupted);
                            }
                            return List.of(persona(
                                    "Tenant-A", "Client-A", "wuyong", "吴用"));
                        })));
            }
            if (!loaderEntered.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("loader did not start");
            }
            releaseLoader.countDown();
            AgentPersonaCatalogCache.CatalogSnapshot expected =
                    futures.getFirst().get(5, TimeUnit.SECONDS);
            for (Future<AgentPersonaCatalogCache.CatalogSnapshot> future : futures) {
                assertSame(expected, future.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, loads.get());
        } finally {
            releaseLoader.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void activeTransactionBypassesAndCannotPoisonSharedSnapshot() {
        AgentPersonaCatalogCache cache = new AgentPersonaCatalogCache();
        AtomicInteger sharedLoads = new AtomicInteger();
        AgentPersonaCatalogCache.CatalogSnapshot shared = cache.get(
                "Tenant-A", "Client-A", () -> {
                    sharedLoads.incrementAndGet();
                    return List.of(persona("Tenant-A", "Client-A", "wuyong", "吴用"));
                });

        TransactionSynchronizationManager.setActualTransactionActive(true);
        AgentPersonaCatalogCache.CatalogSnapshot transactional;
        try {
            transactional = cache.get("Tenant-A", "Client-A",
                    () -> List.of(persona("Tenant-A", "Client-A", "linchong", "林冲")));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        assertEquals("linchong", transactional.entries().getFirst().personaCode());
        assertSame(shared, cache.get("Tenant-A", "Client-A", () -> {
            throw new AssertionError("transactional read must not replace shared cache");
        }));
        assertEquals(1, sharedLoads.get());
    }

    private static AgentPersonaEntity persona(
            String tenantId, String clientId, String code, String name) {
        AgentPersonaEntity persona = new AgentPersonaEntity();
        persona.setTenantId(tenantId);
        persona.setClientId(clientId);
        persona.setPersonaCode(code);
        persona.setName(name);
        persona.setAbilities("[\"planning\"]");
        persona.setRankNo(1);
        persona.setActive(true);
        persona.setSystemAgent(false);
        return persona;
    }
}
