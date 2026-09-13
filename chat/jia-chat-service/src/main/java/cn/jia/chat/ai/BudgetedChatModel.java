package cn.jia.chat.ai;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import cn.jia.core.deadline.RequestDeadline;
import cn.jia.core.deadline.RequestDeadlineContext;
import cn.jia.core.deadline.SafeRequestTimeoutException;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;

/** One-shot provider wrapper with request-aware connect, first-token, and total budgets. */
public final class BudgetedChatModel implements ChatModel {
    private final ChatModel delegate;
    private final AiProviderProperties properties;
    private final ExecutorService synchronousExecutor;
    private final AtomicInteger activeInvocations = new AtomicInteger();

    public BudgetedChatModel(ChatModel delegate, AiProviderProperties properties,
            ExecutorService synchronousExecutor) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.synchronousExecutor = Objects.requireNonNull(synchronousExecutor, "synchronousExecutor");
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Objects.requireNonNull(prompt, "prompt");
        RequestDeadline deadline = RequestDeadlineContext.current().orElse(null);
        AiCallBudget budget = AiCallBudget.resolve(properties, deadline);
        RequestDeadlineContext.Snapshot context = RequestDeadlineContext.capture();
        Future<ChatResponse> future;
        activeInvocations.incrementAndGet();
        try {
            future = synchronousExecutor.submit(context.wrap(() -> {
                try {
                    return delegate.call(prompt);
                } finally {
                    activeInvocations.decrementAndGet();
                }
            }));
        } catch (RejectedExecutionException exception) {
            activeInvocations.decrementAndGet();
            throw new AiProviderCallException(AiFailureCategory.PROVIDER_UNAVAILABLE);
        }
        try {
            return future.get(budget.total().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new AiProviderCallException(AiFailureCategory.TOTAL_TIMEOUT);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new AiProviderCallException(AiFailureCategory.CANCELLED);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof SafeRequestTimeoutException timeout) {
                throw timeout;
            }
            throw AiFailureClassifier.sanitize(cause == null ? exception : cause);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        Objects.requireNonNull(prompt, "prompt");
        RequestDeadline capturedDeadline = RequestDeadlineContext.current().orElse(null);
        return Flux.defer(() -> streamOnce(prompt, capturedDeadline));
    }

    private Flux<ChatResponse> streamOnce(Prompt prompt, RequestDeadline deadline) {
        AiCallBudget budget = AiCallBudget.resolve(properties, deadline);
        AtomicBoolean firstTokenSeen = new AtomicBoolean();
        long startedNanos = System.nanoTime();
        activeInvocations.incrementAndGet();
        Flux<ChatResponse> source;
        try {
            source = Objects.requireNonNull(delegate.stream(prompt), "delegate stream");
        } catch (Throwable failure) {
            activeInvocations.decrementAndGet();
            return Flux.error(sanitize(failure));
        }

        Flux<ChatResponse> firstTokenBounded =
                new FirstTokenTimeoutFlux(source, budget.firstToken(), firstTokenSeen);

        Flux<Signal<ChatResponse>> totalBounded = firstTokenBounded
                .materialize()
                .takeUntilOther(Mono.delay(budget.total()))
                .concatWith(Mono.just(Signal.error(
                        new AiProviderCallException(AiFailureCategory.TOTAL_TIMEOUT))))
                .takeUntil(signal -> signal.isOnComplete() || signal.isOnError());

        return totalBounded
                .<ChatResponse>dematerialize()
                .concatWith(Flux.defer(() -> firstTokenSeen.get()
                        ? Flux.empty()
                        : Flux.error(new AiProviderCallException(AiFailureCategory.PROVIDER_REJECTED))))
                .onErrorMap(BudgetedChatModel::sanitize)
                .doFinally(ignored -> activeInvocations.decrementAndGet());
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

    private static Throwable sanitize(Throwable failure) {
        if (failure instanceof SafeRequestTimeoutException || failure instanceof AiProviderCallException) {
            return failure;
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
