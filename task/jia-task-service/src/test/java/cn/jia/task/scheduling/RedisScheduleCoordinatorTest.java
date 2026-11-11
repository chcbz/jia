package cn.jia.task.scheduling;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Two independent clients against an owned Redis; no configured production endpoint. */
@EnabledIfEnvironmentVariable(named = "CYF_SCHEDULE_REDIS_SERVER", matches = ".+")
class RedisScheduleCoordinatorTest {
    private static Process process;
    private static RedissonClient first, second;
    private final List<RedisScheduleCoordinator> coordinators = new ArrayList<>();
    private FixedTenantAdmissionPolicy scope;

    @BeforeAll static void startRedis() throws Exception {
        Path executable = Path.of(System.getenv("CYF_SCHEDULE_REDIS_SERVER")).toRealPath();
        Path scratch = Files.createDirectory(Path.of(System.getenv("CYF_SCHEDULE_REDIS_DIR")));
        int port;
        try (var socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
        assertNotEquals(6379, port);
        process = new ProcessBuilder(executable.toString(), "--port", Integer.toString(port), "--bind", "127.0.0.1",
                "--save", "", "--appendonly", "no", "--dir", scratch.toString(),
                "--pidfile", scratch.resolve("redis.pid").toString())
                .redirectErrorStream(true).redirectOutput(scratch.resolve("redis.log").toFile()).start();
        try {
            while (true) {
                assertTrue(process.isAlive(), () -> "Owned Redis failed: " + scratch);
                try (var socket = new java.net.Socket("127.0.0.1", port)) { break; }
                catch (java.io.IOException starting) { Thread.sleep(25); }
            }
            first = Redisson.create(config(port)); second = Redisson.create(config(port));
        } catch (Throwable failure) { stopRedis(); throw failure; }
    }
    private static Config config(int port) {
        var config = new Config(); config.setThreads(2).setNettyThreads(2);
        config.useSingleServer().setAddress("redis://127.0.0.1:" + port)
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(4)
                .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2);
        return config;
    }
    @AfterAll static void stopRedis() throws Exception {
        if (first != null) first.shutdown();
        if (second != null) second.shutdown();
        if (process != null && process.isAlive()) { process.destroy(); process.waitFor(); }
    }
    @BeforeEach void partition() { scope = new FixedTenantAdmissionPolicy(UUID.randomUUID().toString(), null); }
    @AfterEach void stopOwners() throws Exception {
        coordinators.forEach(RedisScheduleCoordinator::close);
        for (var coordinator : coordinators) coordinator.stopped().get(5, TimeUnit.SECONDS);
    }
    private RedisScheduleCoordinator start(RedissonClient client, FixedTenantAdmissionPolicy scope, String node,
            LongSupplier step, long recovery) {
        var coordinator = new RedisScheduleCoordinator(client, scope, node, step, recovery, 50);
        coordinators.add(coordinator); coordinator.start(); return coordinator;
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
    @Test void onlyOwnerDiscoversAndCloseReleasesOnCorrectThread() throws Exception {
        var a = new AtomicInteger(); var b = new AtomicInteger();
        var owner = start(first, scope, "", () -> { a.incrementAndGet(); return Long.MAX_VALUE; }, 60000);
        await(() -> a.get() == 1);
        start(second, scope, "", () -> { b.incrementAndGet(); return Long.MAX_VALUE; }, 60000);
        Thread.sleep(150); assertEquals(0, b.get());
        owner.close(); owner.stopped().get(5, TimeUnit.SECONDS);
        await(() -> b.get() == 1);
    }
    @Test void lostClientStopsDiscoveryAndWatchdogTransfersOwnership() throws Exception {
        // A real disconnected Redisson client cannot renew or release its lock.
        // The survivor must wait for Redis expiry, rather than force-unlock it.
        var settings = new Config(second.getConfig()); settings.setLockWatchdogTimeout(1500);
        var disconnected = Redisson.create(settings);
        var oldCalls = new AtomicInteger(); var newCalls = new AtomicInteger();
        try {
            var owner = start(disconnected, scope, "", () -> { oldCalls.incrementAndGet(); return Long.MAX_VALUE; }, 60000);
            await(() -> oldCalls.get() == 1);
            start(second, scope, "", () -> { newCalls.incrementAndGet(); return Long.MAX_VALUE; }, 60000);
            Thread.sleep(100); assertEquals(0, newCalls.get());
            disconnected.shutdown(0, 100, TimeUnit.MILLISECONDS);
            owner.wake();
            await(() -> newCalls.get() == 1);
            assertEquals(1, oldCalls.get(), "Disconnected owner must not discover without Redis ownership");
            owner.close(); owner.stopped().get(5, TimeUnit.SECONDS);
            assertEquals(1, newCalls.get(), "Old cleanup cannot release the new owner's lock");
        } finally { if (!disconnected.isShutdown()) disconnected.shutdown(); }
    }
    @Test void remoteNoticeWakesBeforeFarDeadline() throws Exception {
        var calls = new AtomicInteger();
        start(first, scope, "", () -> { calls.incrementAndGet(); return 60000; }, 60000);
        await(() -> calls.get() == 1);
        new RedisScheduleWakeup(second, scope).accept(new ScheduleKey("client", "rent", "user", ""));
        await(() -> calls.get() == 2);
    }
    @Test void noNoticeStillGetsOneCentralRecoveryWake() throws Exception {
        var calls = new AtomicInteger();
        start(first, scope, "", () -> { calls.incrementAndGet(); return Long.MAX_VALUE; }, 100);
        await(() -> calls.get() >= 2);
    }
    @Test void nodeAndClientNoticesAreFilteredBeforeDiscovery() throws Exception {
        var fixed = new FixedTenantAdmissionPolicy(scope.businessTenantId(), "client");
        var calls = new AtomicInteger();
        start(first, fixed, "node-a", () -> { calls.incrementAndGet(); return Long.MAX_VALUE; }, 60000);
        await(() -> calls.get() == 1);
        var publish = new RedisScheduleWakeup(second, scope);
        publish.accept(new ScheduleKey("other", "rent", "u", "node-a"));
        publish.accept(new ScheduleKey("client", "rent", "u", "node-b"));
        Thread.sleep(150); assertEquals(1, calls.get());
        publish.accept(new ScheduleKey("client", "rent", "u", "node-a"));
        await(() -> calls.get() == 2);
    }
    @Test void noticeDuringDiscoveryIsNotLostOrQueuedPerMessage() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        var owner = start(first, scope, "", () -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            }
            return 60000;
        }, 60000);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 100; i++) owner.wake();
        } finally { release.countDown(); }
        await(() -> calls.get() == 2); Thread.sleep(100); assertEquals(2, calls.get());
    }
    @Test void redisOwnershipFailureNeverFallsBackToDiscovery() throws Exception {
        var broken = org.mockito.Mockito.mock(RedissonClient.class);
        var lock = org.mockito.Mockito.mock(org.redisson.api.RLock.class);
        var topic = org.mockito.Mockito.mock(org.redisson.api.RTopic.class);
        org.mockito.Mockito.when(broken.getLock(org.mockito.ArgumentMatchers.anyString())).thenReturn(lock);
        org.mockito.Mockito.when(broken.getTopic(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(topic);
        var checked = new CountDownLatch(1);
        org.mockito.Mockito.when(lock.isHeldByCurrentThread()).thenAnswer(call -> { checked.countDown(); throw new IllegalStateException("Redis unavailable"); });
        var calls = new AtomicInteger();
        start(broken, scope, "", () -> { calls.incrementAndGet(); return 0; }, 60000);
        assertTrue(checked.await(5, TimeUnit.SECONDS)); assertEquals(0, calls.get());
    }
}
