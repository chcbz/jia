package cn.jia.core.diagnostics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.metrics.StartupStep;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class StartupTimelineTest {
    private final List<String> lines = new ArrayList<>();
    private final AtomicLong tick = new AtomicLong(100);
    private final StartupTimeline timeline = new StartupTimeline(lines::add, tick::get,
            () -> Instant.EPOCH.plusNanos(tick.get()));

    @AfterEach void reset() { StartupTiming.use(null); }

    @Test void exactNestedStartEndAndTags() {
        StartupStep parent = timeline.start("cyf.fixture.parent");
        tick.set(110);
        StartupStep child = timeline.start("spring.beans.instantiate").tag("beanName", "fixtureBean");
        tick.set(140);
        child.end();
        tick.set(160);
        parent.end();
        assertEquals(parent.getId(), child.getParentId());
        assertTrue(lines.stream().anyMatch(s -> s.contains("event=BEGIN id=2 parent=1") && s.contains("monotonic_ns=110")));
        assertTrue(lines.stream().anyMatch(s -> s.contains("event=END id=2 parent=1") && s.contains("duration_ns=30")));
        assertTrue(lines.stream().anyMatch(s -> s.contains("event=END id=1 parent=0") && s.contains("duration_ns=60")));
        assertEquals("fixtureBean", child.getTags().iterator().next().getValue());
        assertTrue(lines.stream().anyMatch(s -> s.contains("outcome=ended")));
    }

    @Test void scopesPreserveReturnAndOriginalFailure() {
        StartupTiming.use(timeline);
        assertEquals("same", StartupTiming.call("cyf.return", () -> "same"));
        IllegalStateException original = new IllegalStateException("secret-not-output");
        assertSame(original, assertThrows(IllegalStateException.class,
                () -> StartupTiming.run("cyf.failure", () -> { throw original; })));
        assertTrue(lines.stream().anyMatch(s -> s.contains("name=cyf.failure") && s.contains("outcome=failed")));
        assertFalse(String.join("\n", lines).contains("secret-not-output"));
    }

    @Test void untrustedTagsNotEvaluatedOrPrinted() {
        StartupStep step = timeline.start("spring.beans.instantiate");
        step.tag("exception", () -> { fail("not allowed to evaluate payload"); return "secret"; });
        step.tag("beanName", "bean\nsecret=value");
        step.end();
        assertFalse(String.join("\n", lines).contains("secret"));
        assertTrue(lines.stream().anyMatch(s -> s.contains("value=omitted")));
    }

    @Test void failingAllowedTagSupplierDoesNotAffectStartup() {
        StartupStep step = timeline.start("spring.beans.instantiate");
        assertDoesNotThrow(() -> step.tag("beanName", () -> { throw new IllegalStateException(); }));
        step.end();
    }

    @Test void outputFailureIsNonFatalAndPreservesBusinessException() {
        StartupTimeline broken = new StartupTimeline(s -> { throw new IllegalStateException(); }, tick::get, Instant::now);
        StartupTiming.use(broken);
        RuntimeException original = new RuntimeException("not-output");
        assertSame(original, assertThrows(RuntimeException.class,
                () -> StartupTiming.run("cyf.failed", () -> { throw original; })));
        assertDoesNotThrow(broken::finish);
    }

    @Test void duplicateEndEmittedOnceAndSiblingParentRestored() {
        StartupStep parent = timeline.start("cyf.parent");
        StartupStep first = timeline.start("cyf.first");
        first.end(); first.end();
        StartupStep second = timeline.start("cyf.second");
        assertEquals(parent.getId(), second.getParentId());
        second.end(); parent.end();
        assertEquals(3, lines.stream().filter(s -> s.contains("event=END")).count());
    }

    @Test void finishReportsOpenSpanWithoutFakeCompletionAndSilencesRuntimeStarts() {
        StartupStep open = timeline.start("cyf.unfinished");
        timeline.finish();
        int size = lines.size();
        timeline.start("cyf.runtime").end();
        assertEquals(size, lines.size());
        assertTrue(lines.get(size - 1).contains("open=1"));
        assertFalse(lines.stream().anyMatch(s -> s.contains("event=END")));
        open.end(); // An already-running asynchronous scope can still report its real end.
        assertTrue(lines.get(lines.size() - 1).contains("event=END"));
    }

    @Test void independentThreadsDoNotAcquireFalseParents() throws Exception {
        List<String> captured = new CopyOnWriteArrayList<>();
        StartupTimeline concurrent = new StartupTimeline(captured::add, System::nanoTime, Instant::now);
        StartupStep parent = concurrent.start("cyf.main");
        Thread worker = new Thread(() -> {
            StartupStep child = concurrent.start("cyf.worker");
            assertNull(child.getParentId());
            child.end();
        });
        worker.start(); worker.join(); parent.end();
        assertTrue(captured.stream().anyMatch(s -> s.contains("event=BEGIN") && s.contains("name=cyf.worker") && s.contains("parent=0")));
    }

    @Test void nativeBeanCreationCoversInitializerWithoutProxyOrReordering() {
        List<String> execution = new ArrayList<>();
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.setApplicationStartup(timeline);
            context.registerBean("fixtureInit", Initializer.class, () -> new Initializer(execution));
            context.refresh();
            assertEquals(List.of("constructor", "initialize"), execution);
            assertEquals(Initializer.class, context.getBean("fixtureInit").getClass());
            assertTrue(lines.stream().anyMatch(s -> s.contains("name=spring.beans.instantiate") && s.contains("event=BEGIN")));
            assertTrue(lines.stream().anyMatch(s -> s.contains("key=beanName value=fixtureInit")));
            assertTrue(lines.stream().anyMatch(s -> s.contains("name=spring.beans.instantiate") && s.contains("event=END")));
        }
    }

    @Test void nativeFailingInitializerStillFailsContext() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.setApplicationStartup(timeline);
            context.registerBean("failingInit", InitializingBean.class,
                    () -> () -> { throw new IllegalStateException("original initialization failure"); });
            assertThrows(RuntimeException.class, context::refresh);
            assertTrue(lines.stream().anyMatch(s -> s.contains("key=beanName value=failingInit")));
        }
    }

    @Test void defaultOffAndExplicitLastFlagWins() {
        assertFalse(StartupTiming.requested(new String[]{}));
        assertFalse(StartupTiming.requested(new String[]{"--debug=true"}));
        assertTrue(StartupTiming.requested(new String[]{"--cyf.startup-tracing.enabled=true"}));
        assertFalse(StartupTiming.requested(new String[]{"--cyf.startup-tracing.enabled=true", "--cyf.startup-tracing.enabled=false"}));
        StartupTiming.run("cyf.off", () -> { });
        assertTrue(lines.isEmpty());
    }

    static final class Initializer implements InitializingBean {
        private final List<String> events;
        Initializer(List<String> events) { this.events = events; events.add("constructor"); }
        @Override public void afterPropertiesSet() { events.add("initialize"); }
    }
}
