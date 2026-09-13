package cn.jia.chat.ai;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import cn.jia.core.deadline.SafeRequestTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** Single-shot provider wrapper that sanitizes failures and observes slow calls without cancelling valid work. */
public final class BudgetedChatModel implements ChatModel {
    private static final Logger log = LoggerFactory.getLogger(BudgetedChatModel.class);

    private final ChatModel delegate;
    private final AiProviderProperties properties;
    private final LongSupplier monotonicClock;
    private final AtomicInteger activeInvocations = new AtomicInteger();

    public BudgetedChatModel(ChatModel delegate, AiProviderProperties properties) {
        this(delegate, properties, System::nanoTime);
    }

    BudgetedChatModel(ChatModel delegate, AiProviderProperties properties, LongSupplier monotonicClock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
        properties.validate();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Objects.requireNonNull(prompt, "prompt");
        long startedNanos = monotonicClock.getAsLong();
        String outcome = "success";
        activeInvocations.incrementAndGet();
        try {
            return delegate.call(prompt);
        } catch (Throwable failure) {
            outcome = "failure";
            throw sanitize(failure);
        } finally {
            activeInvocations.decrementAndGet();
            observeSlow("total", startedNanos, properties.getTotalBudget(), outcome);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        Objects.requireNonNull(prompt, "prompt");
        return Flux.defer(() -> streamOnce(prompt));
    }

    private Flux<ChatResponse> streamOnce(Prompt prompt) {
        long startedNanos = monotonicClock.getAsLong();
        AtomicBoolean firstTokenSeen = new AtomicBoolean();
        activeInvocations.incrementAndGet();
        Flux<ChatResponse> source;
        try {
            source = Objects.requireNonNull(delegate.stream(prompt), "delegate stream");
        } catch (Throwable failure) {
            activeInvocations.decrementAndGet();
            observeSlow("first_token", startedNanos, properties.getFirstTokenBudget(), "failure");
            observeSlow("total", startedNanos, properties.getTotalBudget(), "failure");
            return Flux.error(sanitize(failure));
        }

        return source
                .doOnNext(response -> {
                    if (hasGeneratedText(response) && firstTokenSeen.compareAndSet(false, true)) {
                        observeSlow("first_token", startedNanos, properties.getFirstTokenBudget(), "success");
                    }
                })
                .onErrorMap(BudgetedChatModel::sanitize)
                .doFinally(signal -> {
                    if (!firstTokenSeen.get()) {
                        observeSlow("first_token", startedNanos, properties.getFirstTokenBudget(), signal.name());
                    }
                    observeSlow("total", startedNanos, properties.getTotalBudget(), signal.name());
                    activeInvocations.decrementAndGet();
                });
    }

    static boolean hasGeneratedText(ChatResponse response) {
        if (response == null) {
            return false;
        }
        for (Generation generation : response.getResults()) {
            if (generation != null && generation.getOutput() != null
                    && generation.getOutput().getText() != null
                    && !generation.getOutput().getText().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private void observeSlow(String phase, long startedNanos, Duration threshold, String outcome) {
        long elapsedNanos = monotonicClock.getAsLong() - startedNanos;
        long elapsedMillis = elapsedNanos <= 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        if (elapsedMillis >= threshold.toMillis()) {
            log.warn("Slow external AI call observed: phase={}, elapsedMs={}, thresholdMs={}, outcome={}",
                    phase, elapsedMillis, threshold.toMillis(), outcome);
        }
    }

    private static RuntimeException sanitize(Throwable failure) {
        if (failure instanceof SafeRequestTimeoutException timeout) {
            return timeout;
        }
        if (failure instanceof AiProviderCallException providerFailure) {
            return providerFailure;
        }
        return AiFailureClassifier.sanitize(failure);
    }

    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    public int activeInvocationCount() {
        return activeInvocations.get();
    }

    ChatModel delegate() {
        return delegate;
    }
}
