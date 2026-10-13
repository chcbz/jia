package cn.jia.chat.handler;

import cn.jia.agent.service.AgentManagedSessionLookup.SessionFence;
import cn.jia.agent.service.SkillCapabilityLookup.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SkillExecutionSessionRegistryTest {
    private final SkillExecutionSessionRegistry registry = new SkillExecutionSessionRegistry();
    private final Scope scope = new Scope("0", "Client-A", "Owner-A", "agent");
    private final SessionFence fence = new SessionFence("installation", "host", "runtime", 24);
    private static RuntimeSkill skill(String key, String version) {
        return new RuntimeSkill(key, version, "a".repeat(64), "b".repeat(64), "native-model",
                Readiness.READY, null, "c".repeat(64), null);
    }
    private static Catalog catalog(String revision) { return new Catalog(revision, List.of(skill("analyze", "1.0.0"))); }

    @Test void lookupUsesExactFullScopeAndTrustedFence() {
        registry.register("session", scope, fence, catalog("7"), () -> true);
        var found = registry.lookup(scope, fence);
        assertEquals(State.AVAILABLE, found.state()); assertEquals("session", found.sessionId());
        assertEquals(fence, found.fence()); assertEquals("7", found.catalog().catalogRevision());
        for (var other : List.of(new Scope("1", "Client-A", "Owner-A", "agent"),
                new Scope("0", "Client-a", "Owner-A", "agent"),
                new Scope("0", "Client-A", "Owner-a", "agent"),
                new Scope("0", "Client-A", "Owner-A", "Agent"))) {
            assertEquals(State.DISCONNECTED, registry.lookup(other, fence).state());
        }
        for (var other : List.of(new SessionFence("Installation", "host", "runtime", 24),
                new SessionFence("installation", "Host", "runtime", 24),
                new SessionFence("installation", "host", "Runtime", 24),
                new SessionFence("installation", "host", "runtime", 25))) {
            assertEquals(State.FENCED, registry.lookup(scope, other).state());
            assertNull(registry.lookup(scope, other).catalog());
        }
    }

    @Test void sessionGenerationChangeReplacesAndInvalidatesOldCatalog() {
        registry.register("session", scope, fence, catalog("7"), () -> true);
        var next = new SessionFence("installation", "host", "runtime", 25);
        registry.register("session", scope, next, catalog("8"), () -> true);
        assertEquals(State.FENCED, registry.lookup(scope, fence).state());
        assertEquals("8", registry.lookup(scope, next).catalog().catalogRevision());
        registry.register("session", scope, next, null, () -> true);
        assertEquals(State.INVALID_DECLARATION, registry.lookup(scope, next).state());
        assertNull(registry.lookup(scope, next).catalog());
    }

    @Test void disconnectedFailedPredicateAndRemovalNeverReturnCachedFacts() {
        var live = new AtomicBoolean(true);
        registry.register("session", scope, fence, catalog("7"), live::get);
        assertEquals(State.AVAILABLE, registry.lookup(scope, fence).state());
        live.set(false); assertEquals(State.DISCONNECTED, registry.lookup(scope, fence).state());
        assertNull(registry.lookup(scope, fence).catalog());
        live.set(true); registry.remove("session");
        assertEquals(State.DISCONNECTED, registry.lookup(scope, fence).state());
        registry.register("session", scope, fence, catalog("7"), () -> { throw new IllegalStateException(); });
        assertEquals(State.DISCONNECTED, registry.lookup(scope, fence).state());
    }

    @Test void ambiguityIsRejectedBeforeSelectingByGenerationOrAdvertisedReadiness() {
        var next = new SessionFence("installation", "host", "runtime", 25);
        registry.register("first", scope, fence, catalog("7"), () -> true);
        registry.register("second", scope, next, null, () -> true);
        assertEquals(State.AMBIGUOUS, registry.lookup(scope, fence).state());
        assertEquals(State.AMBIGUOUS, registry.lookup(scope, next).state());
        assertNull(registry.lookup(scope, next).fence());
        registry.remove("second"); assertEquals(State.AVAILABLE, registry.lookup(scope, fence).state());
    }

    @Test void oldSubjectPredicateCannotRemainValidAcrossTrustedFenceSwitch() {
        var current = new AtomicReference<>(fence);
        registry.register("old", scope, fence, catalog("7"), () -> fence.equals(current.get()));
        var next = new SessionFence("installation", "host", "runtime", 25); current.set(next);
        registry.register("new", scope, next, catalog("8"), () -> next.equals(current.get()));
        assertEquals(State.AVAILABLE, registry.lookup(scope, next).state());
        assertEquals(State.FENCED, registry.lookup(scope, fence).state());
    }

    @Test void catalogListIsImmutableAndDuplicateVersionIsRejected() {
        var input = new ArrayList<>(List.of(skill("analyze", "1.0.0")));
        var value = new Catalog("7", input); input.clear();
        assertEquals(1, value.skills().size());
        assertThrows(UnsupportedOperationException.class, () -> value.skills().clear());
        assertThrows(IllegalArgumentException.class, () -> new Catalog("8", List.of(skill("analyze", "1.0.0"), skill("analyze", "1.0.0"))));
        assertEquals(2, new Catalog("8", List.of(skill("analyze", "1.0.0"), skill("analyze", "2.0.0"))).skills().size());
        assertThrows(IllegalArgumentException.class, () -> new Scope("0", "client ", "owner", "agent"));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeSkill("analyze", "1.0.0", "BAD", "b".repeat(64),
                "native-model", Readiness.READY, null, null, null));
    }

    @Test void concurrentReplacementOrDisconnectDuringLookupFailsClosed() {
        var calls = new AtomicInteger();
        registry.register("session", scope, fence, catalog("7"), () -> {
            if (calls.incrementAndGet() == 2) registry.register("session", scope, fence, catalog("8"), () -> true);
            return true;
        });
        // The first predicate may replace the entry during the second live check.
        var result = registry.lookup(scope, fence);
        assertNotEquals("7", result.catalog() == null ? null : result.catalog().catalogRevision());
        assertEquals("8", registry.lookup(scope, fence).catalog().catalogRevision());
        var count = new AtomicInteger();
        registry.register("session", scope, fence, catalog("9"), () -> count.incrementAndGet() == 1);
        assertEquals(State.FENCED, registry.lookup(scope, fence).state());
    }

    @Test void emptyOrUnavailableSkillDirectoryIsNotFabricatedIntoCallablePermission() {
        registry.register("session", scope, fence, new Catalog("7", List.of()), () -> true);
        assertEquals(State.AVAILABLE, registry.lookup(scope, fence).state());
        assertTrue(registry.lookup(scope, fence).catalog().skills().isEmpty());
        var unavailable = new RuntimeSkill("analyze", "1.0.0", "a".repeat(64), "b".repeat(64),
                "local-script", Readiness.UNAVAILABLE, "TOOL_MISSING", null, null);
        registry.register("session", scope, fence, new Catalog("8", List.of(unavailable)), () -> true);
        assertEquals(Readiness.UNAVAILABLE, registry.lookup(scope, fence).catalog().skills().getFirst().readiness());
        assertEquals("TOOL_MISSING", registry.lookup(scope, fence).catalog().skills().getFirst().reasonCode());
    }
}
