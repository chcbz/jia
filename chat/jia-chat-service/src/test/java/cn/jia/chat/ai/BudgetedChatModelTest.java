package cn.jia.chat.ai;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import cn.jia.core.deadline.RequestDeadline;
import cn.jia.core.deadline.RequestDeadlineContext;
import cn.jia.core.deadline.SafeRequestTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetedChatModelTest {
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void exactPromptAndProviderAreInvokedOnceForSyncAndStream() {
        Prompt prompt = new Prompt("private prompt");
        AtomicReference<Prompt> syncPrompt = new AtomicReference<>();
        AtomicReference<Prompt> streamPrompt = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        ChatModel delegate = new ChatModel() {
            @Override
            public ChatResponse call(Prompt actual) {
                calls.incrementAndGet();
                syncPrompt.set(actual);
                return response("sync");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt actual) {
                calls.incrementAndGet();
                streamPrompt.set(actual);
                return Flux.just(response("stream"));
            }
        };
        BudgetedChatModel model = new BudgetedChatModel(delegate, properties(), executor);

        model.call(prompt);
        model.stream(prompt).blockLast(Duration.ofSeconds(2));

        assertEquals(2, calls.get());
        assertSame(prompt, syncPrompt.get());
        assertSame(prompt, streamPrompt.get());
        assertSame(delegate, model.delegate());
        assertEquals(0, model.activeInvocationCount());
    }

    @Test
    void exhaustedRequestDeadlineFailsBeforeProviderInvocation() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel delegate = prompt -> {
            calls.incrementAndGet();
            return response("never");
        };
        BudgetedChatModel model = new BudgetedChatModel(delegate, properties(), executor);

        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(RequestDeadline.start(0))) {
            SafeRequestTimeoutException failure = assertThrows(SafeRequestTimeoutException.class,
                    () -> model.call(new Prompt("private prompt")));
            assertEquals(SafeRequestTimeoutException.Dependency.AI, failure.dependency());
            assertEquals(SafeRequestTimeoutException.WorkState.NOT_STARTED, failure.workState());
        }

        assertEquals(0, calls.get());
        assertEquals(0, model.activeInvocationCount());
    }

    @Test
    void remainingDeadlineCapsEveryBudgetSlice() {
        AiProviderProperties properties = properties();
        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(RequestDeadline.start(90))) {
            AiCallBudget budget = AiCallBudget.resolve(properties,
                    RequestDeadlineContext.current().orElseThrow());
            assertTrue(budget.connect().toMillis() <= 90);
            assertTrue(budget.firstToken().toMillis() <= 90);
            assertTrue(budget.total().toMillis() <= 90);
            assertTrue(budget.connect().compareTo(budget.firstToken()) <= 0);
            assertTrue(budget.firstToken().compareTo(budget.total()) <= 0);
        }
    }

    @Test
    void metadataOnlyFramesDoNotSatisfyFirstTokenAndTimeoutCancelsUpstream() {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean cancelled = new AtomicBoolean();
        ChatModel delegate = new StreamingOnlyModel(prompt -> {
            calls.incrementAndGet();
            return Flux.just(response(""))
                    .concatWith(Flux.never())
                    .doOnCancel(() -> cancelled.set(true));
        });
        BudgetedChatModel model = new BudgetedChatModel(delegate, properties(), executor);

        AiProviderCallException failure = assertThrows(AiProviderCallException.class,
                () -> model.stream(new Prompt("private prompt")).blockLast(Duration.ofSeconds(2)));

        assertEquals(AiFailureCategory.FIRST_TOKEN_TIMEOUT, failure.category());
        assertEquals(1, calls.get());
        assertTrue(cancelled.get());
        assertEquals(0, model.activeInvocationCount());
        assertFalse(failure.getMessage().contains("private prompt"));
    }

    @Test
    void totalTimeoutAfterFirstTokenCancelsAndCleansUp() {
        AtomicBoolean cancelled = new AtomicBoolean();
        ChatModel delegate = new StreamingOnlyModel(prompt -> Flux.just(response("token"))
                .concatWith(Flux.never())
                .doOnCancel(() -> cancelled.set(true)));
        BudgetedChatModel model = new BudgetedChatModel(delegate, properties(), executor);

        AiProviderCallException failure = assertThrows(AiProviderCallException.class,
                () -> model.stream(new Prompt("private prompt")).blockLast(Duration.ofSeconds(2)));

        assertEquals(AiFailureCategory.TOTAL_TIMEOUT, failure.category());
        assertTrue(cancelled.get());
        assertEquals(0, model.activeInvocationCount());
    }

    @Test
    void downstreamCancellationCancelsProviderAndCleansUp() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch subscribed = new CountDownLatch(1);
        ChatModel delegate = new StreamingOnlyModel(prompt -> Flux.<ChatResponse>never()
                .doOnSubscribe(ignored -> subscribed.countDown())
                .doOnCancel(() -> cancelled.set(true)));
        BudgetedChatModel model = new BudgetedChatModel(delegate, properties(), executor);

        Disposable subscription = model.stream(new Prompt("private prompt")).subscribe();
        assertTrue(subscribed.await(1, TimeUnit.SECONDS));
        subscription.dispose();

        assertTrue(cancelled.get());
        assertEquals(0, model.activeInvocationCount());
    }

    @Test
    void synchronousTimeoutInterruptsTaskAndDoesNotRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        ChatModel delegate = prompt -> {
            calls.incrementAndGet();
            started.countDown();
            try {
                new CountDownLatch(1).await();
                return response("never");
            } catch (InterruptedException exception) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new RuntimeException(exception);
            }
        };
        BudgetedChatModel model = new BudgetedChatModel(delegate, properties(), executor);

        AiProviderCallException failure = assertThrows(AiProviderCallException.class,
                () -> model.call(new Prompt("private prompt")));

        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertEquals(AiFailureCategory.TOTAL_TIMEOUT, failure.category());
        assertEquals(1, calls.get());
        awaitTrue(interrupted);
        awaitActiveZero(model);
    }

    @Test
    void providerFailureIsClassifiedWithoutRetainingSensitiveCause() {
        ChatModel delegate = prompt -> {
            throw new TransientAiException("Authorization bearer-secret provider-body");
        };
        BudgetedChatModel model = new BudgetedChatModel(delegate, properties(), executor);

        AiProviderCallException failure = assertThrows(AiProviderCallException.class,
                () -> model.call(new Prompt("private prompt")));

        assertEquals(AiFailureCategory.PROVIDER_UNAVAILABLE, failure.category());
        assertFalse(failure.retryable());
        assertEquals(null, failure.getCause());
        assertFalse(failure.getMessage().contains("bearer-secret"));
        assertFalse(failure.getMessage().contains("provider-body"));
    }

    @Test
    void classifierUsesFiniteSafeAuthRateAndTransportCategories() {
        assertEquals(AiFailureCategory.AUTHENTICATION,
                AiFailureClassifier.classify(new UnauthorizedException("secret")));
        assertEquals(AiFailureCategory.RATE_LIMITED,
                AiFailureClassifier.classify(new RateLimitException("secret")));
        assertEquals(AiFailureCategory.PROVIDER_UNAVAILABLE,
                AiFailureClassifier.classify(new IOException("provider body")));
    }

    private static AiProviderProperties properties() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setEnabled(true);
        properties.setProvider(AiProviderProperties.Provider.OPENAI);
        properties.setConnectBudget(Duration.ofMillis(40));
        properties.setFirstTokenBudget(Duration.ofMillis(100));
        properties.setTotalBudget(Duration.ofMillis(180));
        properties.setSafetyMargin(Duration.ZERO);
        properties.validate();
        return properties;
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static void awaitTrue(AtomicBoolean value) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!value.get() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(value.get());
    }

    private static void awaitActiveZero(BudgetedChatModel model) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (model.activeInvocationCount() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(0, model.activeInvocationCount());
    }

    private static final class StreamingOnlyModel implements ChatModel {
        private final java.util.function.Function<Prompt, Flux<ChatResponse>> stream;

        private StreamingOnlyModel(java.util.function.Function<Prompt, Flux<ChatResponse>> stream) {
            this.stream = stream;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new AssertionError("sync call was not expected");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return stream.apply(prompt);
        }
    }

    private static final class TransientAiException extends RuntimeException {
        private TransientAiException(String message) {
            super(message);
        }
    }

    private static final class UnauthorizedException extends RuntimeException {
        private UnauthorizedException(String message) {
            super(message);
        }
    }

    private static final class RateLimitException extends RuntimeException {
        private RateLimitException(String message) {
            super(message);
        }
    }
}
