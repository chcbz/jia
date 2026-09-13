package cn.jia.core.diagnostics;

import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.core.metrics.StartupStep;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Streaming, startup-only spans: no actuator exposure, bean proxies, or payload logging. */
public final class StartupTimeline implements ApplicationStartup {
    private static final Set<String> TAG_KEYS = Set.of(
            "beanName", "beanType", "postProcessor", "listener", "mainApplicationClass", "outcome");
    private final AtomicLong ids = new AtomicLong();
    private final AtomicLong openSteps = new AtomicLong();
    private final AtomicLong outputNanos = new AtomicLong();
    private final AtomicLong outputFailures = new AtomicLong();
    private final ThreadLocal<Deque<Step>> stacks = ThreadLocal.withInitial(ArrayDeque::new);
    private final Consumer<String> output;
    private final LongSupplier nanos;
    private final Supplier<Instant> wall;
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    public StartupTimeline() {
        this(System.err::println, System::nanoTime, Instant::now);
    }

    StartupTimeline(Consumer<String> output, LongSupplier nanos, Supplier<Instant> wall) {
        this.output = output;
        this.nanos = nanos;
        this.wall = wall;
    }

    @Override
    public StartupStep start(String name) {
        if (!accepting.get()) return ApplicationStartup.DEFAULT.start(name);
        Deque<Step> stack = stacks.get();
        Step step;
        synchronized (stack) {
            Step parent = stack.peek();
            step = new Step(ids.incrementAndGet(), parent == null ? null : parent.id,
                    safe(name), stack);
            stack.push(step);
        }
        openSteps.incrementAndGet();
        step.emit("BEGIN", step.startedNanos, step.startedWall, "");
        return step;
    }

    /** Stop recording new runtime work; unfinished spans remain explicitly unfinished. */
    public void finish() {
        if (accepting.getAndSet(false)) {
            write("CYF_STARTUP_SUMMARY steps=" + ids.get() + " open=" + openSteps.get()
                    + " output_ns=" + outputNanos.get() + " output_failures=" + outputFailures.get());
            stacks.remove();
        }
    }

    private void write(String line) {
        long begin = nanos.getAsLong();
        try {
            output.accept(line);
        } catch (RuntimeException ignored) {
            // Diagnostics must not alter the application's result or exception.
            outputFailures.incrementAndGet();
        } finally {
            outputNanos.addAndGet(nanos.getAsLong() - begin);
        }
    }

    private static String safe(String value) {
        return value != null && value.matches("[A-Za-z0-9_.$@#:/-]+") ? value : "omitted";
    }

    private final class Step implements StartupStep {
        private final long id;
        private final Long parent;
        private final String name;
        private final long thread = Thread.currentThread().threadId();
        private final long startedNanos = nanos.getAsLong();
        private final Instant startedWall = wall.get();
        private final Deque<Step> ownerStack;
        private final Map<String, String> tags = new LinkedHashMap<>();
        private final AtomicBoolean ended = new AtomicBoolean();

        Step(long id, Long parent, String name, Deque<Step> ownerStack) {
            this.id = id;
            this.parent = parent;
            this.name = name;
            this.ownerStack = ownerStack;
        }

        @Override public String getName() { return name; }
        @Override public long getId() { return id; }
        @Override public Long getParentId() { return parent; }

        @Override
        public StartupStep tag(String key, String value) {
            if (!TAG_KEYS.contains(key) || ended.get()) return this;
            String sanitized = safe(value);
            synchronized (tags) { tags.put(key, sanitized); }
            emit("TAG", nanos.getAsLong(), wall.get(), " key=" + key + " value=" + sanitized);
            return this;
        }

        @Override
        public StartupStep tag(String key, Supplier<String> value) {
            if (!TAG_KEYS.contains(key) || ended.get()) return this;
            try { return tag(key, value.get()); }
            catch (RuntimeException ignored) { return tag(key, "omitted"); }
        }

        @Override
        public Tags getTags() {
            ArrayList<Tag> copy = new ArrayList<>();
            synchronized (tags) {
                tags.forEach((key, value) -> copy.add(new Tag() {
                    @Override public String getKey() { return key; }
                    @Override public String getValue() { return value; }
                }));
            }
            return copy::iterator;
        }

        @Override
        public void end() {
            if (!ended.compareAndSet(false, true)) return;
            long endNanos = nanos.getAsLong();
            Instant endWall = wall.get();
            synchronized (ownerStack) { ownerStack.remove(this); }
            openSteps.decrementAndGet();
            String outcome;
            synchronized (tags) { outcome = tags.getOrDefault("outcome", "ended"); }
            // Framework end() proves a scope closed, not that it succeeded.
            emit("END", endNanos, endWall,
                    " duration_ns=" + (endNanos - startedNanos) + " outcome=" + outcome);
        }

        private void emit(String event, long tick, Instant instant, String extra) {
            write("CYF_STARTUP_STEP event=" + event + " id=" + id + " parent="
                    + (parent == null ? 0 : parent) + " thread=" + thread + " name=" + name
                    + " wall=" + instant + " monotonic_ns=" + tick + extra);
        }
    }
}
