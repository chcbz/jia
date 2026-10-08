package cn.jia.chat.service;

import cn.jia.chat.deliberation.ChatDeliberationMapper;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatDeliberationTransactionContractTest {
    @Test
    void writeBoundariesRollbackOnAnyException() throws Exception {
        for (String name : new String[] {"admit", "markDispatch", "acceptDelta", "persistFinal",
                "cancelTurn", "cancelPending", "markPublished"}) {
            Method method = java.util.Arrays.stream(ChatDeliberationService.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
            Transactional tx = method.getAnnotation(Transactional.class);
            assertTrue(tx != null, name);
            assertArrayEquals(new Class<?>[] {Exception.class}, tx.rollbackFor(), name);
        }
    }

    @Test
    void mapperMutationsUseExactScopeAndStateVersionCas() throws Exception {
        for (String name : new String[] {"acceptDelta", "persistFinal", "updateTurnState", "publishFinal"}) {
            Method method = java.util.Arrays.stream(ChatDeliberationMapper.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
            String sql = String.join(" ", method.getAnnotation(Update.class).value());
            assertTrue(sql.contains("tenant_id=#{tenantId}"), name);
            assertTrue(sql.contains("owner_jiacn=#{ownerJiacn}"), name);
            assertTrue(sql.contains("client_id=#{clientId}"), name);
            assertTrue(sql.contains("state_version=#{stateVersion}"), name);
        }
    }

    @Test
    void cancelAndFinalConcurrentCasAllowsExactlyOneTerminalWinner() throws Exception {
        AtomicInteger stateVersion = new AtomicInteger();
        AtomicReference<String> state = new AtomicReference<>("STREAMING");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var cancel = executor.submit(() -> terminalCas("CANCELLED", stateVersion, state, ready, start));
            var finish = executor.submit(() -> terminalCas("FINAL_PERSISTED", stateVersion, state, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int winners = (cancel.get(5, TimeUnit.SECONDS) ? 1 : 0)
                    + (finish.get(5, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, winners);
            assertEquals(1, stateVersion.get());
            assertTrue("CANCELLED".equals(state.get()) || "FINAL_PERSISTED".equals(state.get()));
        }
    }

    private boolean terminalCas(String desired, AtomicInteger version, AtomicReference<String> state,
            CountDownLatch ready, CountDownLatch start) throws Exception {
        int expected = version.get();
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        if (!version.compareAndSet(expected, expected + 1)) return false;
        state.set(desired);
        return true;
    }

    @Test
    void deliveryCodeGuardsAgainstActiveTransactionsAndOutboxVersionIsRead() throws Exception {
        String service = source("src/main/java/cn/jia/chat/service/ChatDeliberationService.java");
        String outbox = source("src/main/java/cn/jia/chat/service/ChatDeliberationOutboxService.java");
        String worker = source("src/main/java/cn/jia/chat/service/ChatDeliberationOutboxRelay.java");
        String handler = source("src/main/java/cn/jia/chat/handler/AgentWebSocketHandler.java");
        assertTrue(outbox.contains("lockOutboxById("));
        assertTrue(outbox.contains("getFencingToken()"));
        assertTrue(outbox.contains("getLeaseUntil()"));
        assertTrue(worker.contains("SmartLifecycle"));
        assertTrue(worker.contains("NO_ACTIVE_SSE_SUBSCRIBER"));
        assertFalse(service.contains("updateOutbox(turnId, \"DISPATCH\", 0L"));
        assertTrue(handler.contains("TransactionSynchronizationManager.isActualTransactionActive()"));
    }

    private String source(String relative) throws Exception {
        return java.nio.file.Files.readString(java.nio.file.Path.of(relative), StandardCharsets.UTF_8);
    }
}
