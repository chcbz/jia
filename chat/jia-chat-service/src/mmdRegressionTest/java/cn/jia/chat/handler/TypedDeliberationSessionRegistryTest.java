package cn.jia.chat.handler;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class TypedDeliberationSessionRegistryTest {
    private final TypedDeliberationSessionRegistry registry=new TypedDeliberationSessionRegistry();
    private final TypedDeliberationSessionRegistry.Scope scope=
            new TypedDeliberationSessionRegistry.Scope("0","owner","client");
    @Test void exactOneCurrentReadySessionIsSelected() {
        registry.register("s1","0","owner","client","agent",
                TypedDeliberationDeclarationTest.declaration("READY"));
        var ready=registry.requireSingleReady(scope,"agent");
        assertEquals("s1",ready.sessionId());assertEquals("agent",ready.agentId());
    }
    @Test void duplicateReadySessionsAreAmbiguous() {
        registry.register("s1","0","owner","client","agent",TypedDeliberationDeclarationTest.declaration("READY"));
        registry.register("s2","0","owner","client","agent",TypedDeliberationDeclarationTest.declaration("READY"));
        assertThrows(IllegalStateException.class,()->registry.requireSingleReady(scope,"agent"));
    }
    @Test void revokedBindingAndUnavailableDeclarationAreRejected() {
        AtomicBoolean current=new AtomicBoolean(true);
        registry.register("s1","0","owner","client","agent",TypedDeliberationDeclarationTest.declaration("READY"),current::get);
        current.set(false);assertThrows(IllegalStateException.class,()->registry.requireSingleReady(scope,"agent"));
        registry.remove("s1");registry.register("s2","0","owner","client","agent",TypedDeliberationDeclarationTest.declaration("UNAVAILABLE"));
        assertThrows(IllegalStateException.class,()->registry.requireSingleReady(scope,"agent"));
    }
    @Test void onlyAbsentOrExplicitlyUnavailableRuntimeIsAwaitable() {
        assertThrows(TypedDeliberationSessionRegistry.RuntimeNotReadyException.class,
                ()->registry.requireSingleReady(scope,"agent"));
        registry.register("s1","0","owner","client","agent",TypedDeliberationDeclarationTest.declaration("UNAVAILABLE"));
        assertThrows(TypedDeliberationSessionRegistry.RuntimeNotReadyException.class,
                ()->registry.requireSingleReady(scope,"agent"));
        registry.register("s1","0","owner","client","agent",TypedDeliberationDeclarationTest.declaration("READY"));
        assertEquals("s1",registry.requireSingleReady(scope,"agent").sessionId());
    }
    @Test void undeclaredAndMalformedRuntimeArePermanentNotAwaitable() {
        for(Object declaration:new Object[]{null, java.util.Map.of("schemaVersion",1)}) {
            registry.register("s1","0","owner","client","agent",declaration);
            var failure=assertThrows(IllegalStateException.class,()->registry.requireSingleReady(scope,"agent"));
            assertFalse(failure instanceof TypedDeliberationSessionRegistry.RuntimeNotReadyException);
        }
    }
    @Test void ambiguityIsPermanentEvenWhenOneDeclarationIsUnavailable() {
        registry.register("s1","0","owner","client","agent",TypedDeliberationDeclarationTest.declaration("UNAVAILABLE"));
        registry.register("s2","0","owner","client","agent",TypedDeliberationDeclarationTest.declaration("READY"));
        var failure=assertThrows(IllegalStateException.class,()->registry.requireSingleReady(scope,"agent"));
        assertFalse(failure instanceof TypedDeliberationSessionRegistry.RuntimeNotReadyException);
    }
    @Test void revokedSessionCannotWinButExactReconnectedSessionCanResume() {
        AtomicBoolean current=new AtomicBoolean(true);
        registry.register("old","0","owner","client","agent",TypedDeliberationDeclarationTest.declaration("READY"),current::get);
        current.set(false);
        assertThrows(TypedDeliberationSessionRegistry.RuntimeNotReadyException.class,
                ()->registry.requireSingleReady(scope,"agent"));
        registry.register("foreign","0","other-owner","client","agent",TypedDeliberationDeclarationTest.declaration("READY"));
        assertThrows(TypedDeliberationSessionRegistry.RuntimeNotReadyException.class,
                ()->registry.requireSingleReady(scope,"agent"));
        registry.register("new","0","owner","client","agent",TypedDeliberationDeclarationTest.declaration("READY"));
        assertEquals("new",registry.requireSingleReady(scope,"agent").sessionId());
        registry.remove("old");
        assertEquals("new",registry.requireSingleReady(scope,"agent").sessionId());
    }
    @Test void oneSocketMayHoldDistinctAgentDeclarationsWithoutOverwrite() {
        registry.register("s1","0","owner","client","agent-a",TypedDeliberationDeclarationTest.declaration("READY"));
        registry.register("s1","0","owner","client","agent-b",TypedDeliberationDeclarationTest.declaration("READY"));
        assertEquals("agent-a",registry.requireSingleReady(scope,"agent-a").agentId());
        assertEquals("agent-b",registry.requireSingleReady(scope,"agent-b").agentId());
        assertEquals(2,registry.size());
        registry.remove("s1");
        assertEquals(0,registry.size());
    }
    @Test void scopeAndAgentAreExactAndRemovalIsSessionLocal() {
        registry.register("s1","0","owner","client","agent",TypedDeliberationDeclarationTest.declaration("READY"));
        assertThrows(IllegalStateException.class,()->registry.requireSingleReady(new TypedDeliberationSessionRegistry.Scope("0","Owner","client"),"agent"));
        registry.remove("other");assertEquals(1,registry.size());registry.remove("s1");assertEquals(0,registry.size());
    }
}
