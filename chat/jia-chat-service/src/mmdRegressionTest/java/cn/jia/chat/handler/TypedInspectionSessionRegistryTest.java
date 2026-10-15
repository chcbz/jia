package cn.jia.chat.handler;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TypedInspectionSessionRegistryTest {
    private final TypedInspectionSessionRegistry registry = new TypedInspectionSessionRegistry();
    private final TypedInspectionSessionRegistry.Scope scope =
            new TypedInspectionSessionRegistry.Scope("0", "owner", "client");

    @Test
    void requiresExactlyOneCurrentReadyExactScopeSession() {
        AtomicBoolean current = new AtomicBoolean(true);
        registry.register("session-1", "0", "owner", "client", "agent",
                TypedInspectionDeclarationTest.declaration(true), current::get);
        assertEquals("session-1", registry.requireSingleReady(scope, "agent").sessionId());
        assertThrows(IllegalStateException.class, () -> registry.requireSingleReady(
                new TypedInspectionSessionRegistry.Scope("0", "Owner", "client"), "agent"));
        current.set(false);
        assertThrows(IllegalStateException.class, () -> registry.requireSingleReady(scope, "agent"));
    }

    @Test
    void ambiguityDisabledAndRemovalFailClosed() {
        registry.register("session-1", "0", "owner", "client", "agent",
                TypedInspectionDeclarationTest.declaration(true), () -> true);
        registry.register("session-2", "0", "owner", "client", "agent",
                TypedInspectionDeclarationTest.declaration(true), () -> true);
        assertThrows(IllegalStateException.class, () -> registry.requireSingleReady(scope, "agent"));
        registry.remove("session-2");
        assertEquals("session-1", registry.requireSingleReady(scope, "agent").sessionId());
        registry.remove("session-1");
        registry.register("session-3", "0", "owner", "client", "agent",
                TypedInspectionDeclarationTest.declaration(false), () -> true);
        assertThrows(IllegalStateException.class, () -> registry.requireSingleReady(scope, "agent"));
    }
}
