package cn.jia.agent.service.impl;

import cn.jia.agent.entity.AgentTaskEventWriteResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C01B-E22 isolated MySQL evidence for serialized, gap-free task event versions. */
@EnabledIfEnvironmentVariable(named = "C01B_MYSQL_URL", matches = ".+")
class AgentTaskEventMySqlConcurrencyTest {

    @Test
    void concurrentSuccessfulWritersCommitUniqueContiguousVersionsAndCurrentEqualsMax()
            throws Exception {
        try (AgentTaskEventTestFixture fixture = AgentTaskEventTestFixture.mysql("concurrency")) {
            fixture.seedTask();
            int threads = 6;
            int writesPerThread = 6;
            int total = threads * writesPerThread;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            Set<Long> versions = Collections.synchronizedSet(new HashSet<>());
            List<Future<?>> futures = new ArrayList<>();

            try {
                for (int thread = 0; thread < threads; thread++) {
                    int threadIndex = thread;
                    futures.add(executor.submit(() -> {
                        start.await();
                        for (int write = 0; write < writesPerThread; write++) {
                            AgentTaskEventWriteResult result = fixture.writer.append(
                                    fixture.command("evt-mysql-" + threadIndex + "-" + write));
                            versions.add(result.getEventVersion());
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> future : futures) {
                    future.get(60, TimeUnit.SECONDS);
                }
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }

            assertEquals(total, versions.size());
            for (long expected = 1; expected <= total; expected++) {
                assertTrue(versions.contains(expected), "missing committed version " + expected);
            }
            assertEquals(total, fixture.eventCount());
            assertEquals((long) total, fixture.currentEventVersion());
            assertEquals(fixture.maxEventVersion(), fixture.currentEventVersion());
            assertEquals(
                    java.util.stream.LongStream.rangeClosed(1, total).boxed().toList(),
                    fixture.eventVersions());
        }
    }
}
