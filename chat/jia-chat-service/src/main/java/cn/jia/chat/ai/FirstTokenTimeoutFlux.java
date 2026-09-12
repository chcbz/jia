package cn.jia.chat.ai;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.reactivestreams.Subscription;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Operators;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

/** A single-subscription, backpressure-preserving absolute first-generated-token gate. */
final class FirstTokenTimeoutFlux extends Flux<ChatResponse> {
    private final Flux<ChatResponse> source;
    private final Duration timeout;
    private final AtomicBoolean firstTokenSeen;

    FirstTokenTimeoutFlux(Flux<ChatResponse> source, Duration timeout, AtomicBoolean firstTokenSeen) {
        this.source = Objects.requireNonNull(source, "source");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.firstTokenSeen = Objects.requireNonNull(firstTokenSeen, "firstTokenSeen");
    }

    @Override
    public void subscribe(CoreSubscriber<? super ChatResponse> actual) {
        FirstTokenSubscriber parent = new FirstTokenSubscriber(actual, firstTokenSeen);
        actual.onSubscribe(parent);
        parent.start(timeout);
        source.subscribe(parent);
    }

    private static final class FirstTokenSubscriber extends Operators.DeferredSubscription
            implements CoreSubscriber<ChatResponse> {
        private final CoreSubscriber<? super ChatResponse> actual;
        private final AtomicBoolean firstTokenSeen;
        private final AtomicBoolean done = new AtomicBoolean();
        private volatile Disposable timeoutTask;

        private FirstTokenSubscriber(CoreSubscriber<? super ChatResponse> actual,
                AtomicBoolean firstTokenSeen) {
            this.actual = actual;
            this.firstTokenSeen = firstTokenSeen;
        }

        private void start(Duration timeout) {
            timeoutTask = Schedulers.parallel().schedule(this::timeout,
                    timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            set(subscription);
        }

        @Override
        public synchronized void onNext(ChatResponse response) {
            if (done.get()) {
                Operators.onNextDropped(response, currentContext());
                return;
            }
            if (BudgetedChatModel.hasGeneratedText(response)
                    && firstTokenSeen.compareAndSet(false, true)) {
                disposeTimeout();
            }
            if (!done.get()) {
                actual.onNext(response);
            }
        }

        @Override
        public synchronized void onError(Throwable failure) {
            if (done.compareAndSet(false, true)) {
                disposeTimeout();
                actual.onError(failure);
            } else {
                Operators.onErrorDropped(failure, currentContext());
            }
        }

        @Override
        public synchronized void onComplete() {
            if (done.compareAndSet(false, true)) {
                disposeTimeout();
                actual.onComplete();
            }
        }

        @Override
        public Context currentContext() {
            return actual.currentContext();
        }

        @Override
        public synchronized void cancel() {
            if (done.compareAndSet(false, true)) {
                disposeTimeout();
            }
            super.cancel();
        }

        private synchronized void timeout() {
            if (firstTokenSeen.get()) {
                return;
            }
            if (done.compareAndSet(false, true)) {
                super.cancel();
                actual.onError(new AiProviderCallException(AiFailureCategory.FIRST_TOKEN_TIMEOUT));
            }
        }

        private void disposeTimeout() {
            Disposable task = timeoutTask;
            if (task != null) {
                task.dispose();
            }
        }
    }
}
