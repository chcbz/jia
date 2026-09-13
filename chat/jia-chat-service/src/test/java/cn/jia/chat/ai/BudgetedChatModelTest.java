package cn.jia.chat.ai;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.jia.core.deadline.RequestDeadline;
import cn.jia.core.deadline.RequestDeadlineContext;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
        BudgetedChatModel model = new BudgetedChatModel(delegate, properties());

        model.call(prompt);
        model.stream(prompt).blockLast(Duration.ofSeconds(2));

        assertEquals(2, calls.get());
        assertSame(prompt, syncPrompt.get());
        assertSame(prompt, streamPrompt.get());
        assertSame(delegate, model.delegate());
        assertEquals(0, model.activeInvocationCount());
    }

    @Test
    void exhaustedRequestDeadlineDoesNotRefuseNewAiWork() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel delegate = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                calls.incrementAndGet();
                return response("available-sync");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                calls.incrementAndGet();
                return Flux.just(response("available-stream"));
            }
        };
        BudgetedChatModel model = new BudgetedChatModel(delegate, properties());

        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(RequestDeadline.start(0))) {
            assertEquals("available-sync", model.call(new Prompt("private prompt"))
                    .getResults().get(0).getOutput().getText());
            assertEquals("available-stream", model.stream(new Prompt("private prompt"))
                    .blockLast(Duration.ofSeconds(1)).getResults().get(0).getOutput().getText());
        }

        assertEquals(2, calls.get());
        assertEquals(0, model.activeInvocationCount());
    }

    @Test
    void slowSynchronousCallCompletesAndEmitsOnlySafeObservation() {
        MutableClock clock = new MutableClock();
        ChatModel delegate = prompt -> {
            clock.advanceMillis(250);
            return response("slow-valid-result");
        };
        BudgetedChatModel model = new BudgetedChatModel(delegate, properties(), clock);

        List<ILoggingEvent> events = captureLogs(() -> assertEquals("slow-valid-result",
                model.call(new Prompt("private-prompt-value")).getResults().get(0).getOutput().getText()));

        assertEquals(1, events.size());
        String message = events.get(0).getFormattedMessage();
        assertTrue(message.contains("phase=total"));
        assertFalse(message.contains("private-prompt-value"));
        assertFalse(message.contains("slow-valid-result"));
    }

    @Test
    void slowFirstGeneratedTokenIsObservedWithoutCancellingUpstream() {
        MutableClock clock = new MutableClock();
        AtomicBoolean cancelled = new AtomicBoolean();
        ChatModel delegate = new StreamingOnlyModel(prompt -> Flux.defer(() -> {
            clock.advanceMillis(150);
            return Flux.just(response("token"), response("later"));
        }).doOnCancel(() -> cancelled.set(true)));
        BudgetedChatModel model = new BudgetedChatModel(delegate, properties(), clock);

        List<ILoggingEvent> events = captureLogs(() -> assertEquals("later",
                model.stream(new Prompt("private prompt")).blockLast(Duration.ofSeconds(1))
                        .getResults().get(0).getOutput().getText()));

        assertFalse(cancelled.get());
        assertEquals(0, model.activeInvocationCount());
        assertTrue(events.stream().anyMatch(event -> event.getFormattedMessage().contains("phase=first_token")));
    }

    @Test
    void downstreamCancellationStillCancelsProviderAndCleansUp() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch subscribed = new CountDownLatch(1);
        ChatModel delegate = new StreamingOnlyModel(prompt -> Flux.<ChatResponse>never()
                .doOnSubscribe(ignored -> subscribed.countDown())
                .doOnCancel(() -> cancelled.set(true)));
        BudgetedChatModel model = new BudgetedChatModel(delegate, properties());

        Disposable subscription = model.stream(new Prompt("private prompt")).subscribe();
        assertTrue(subscribed.await(1, TimeUnit.SECONDS));
        subscription.dispose();

        assertTrue(cancelled.get());
        assertEquals(0, model.activeInvocationCount());
    }

    @Test
    void providerFailureIsClassifiedWithoutRetainingSensitiveCause() {
        ChatModel delegate = prompt -> {
            throw new TransientAiException("Authorization bearer-secret provider-body");
        };
        BudgetedChatModel model = new BudgetedChatModel(delegate, properties());

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
        properties.setTotalBudget(Duration.ofMillis(200));
        properties.validate();
        return properties;
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static List<ILoggingEvent> captureLogs(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(BudgetedChatModel.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try {
            action.run();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }
    }

    private static final class MutableClock implements LongSupplier {
        private long now;

        @Override
        public long getAsLong() {
            return now;
        }

        private void advanceMillis(long millis) {
            now += TimeUnit.MILLISECONDS.toNanos(millis);
        }
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
