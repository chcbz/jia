package cn.jia.chat.handler;

import cn.jia.agent.service.NativeTextFileRuntimeLookup.Scope;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class NativeTextFileSessionRegistryTest {
    final NativeTextFileSessionRegistry registry = new NativeTextFileSessionRegistry();
    final Scope scope = new Scope("tenant", "client", "owner", "agent");
    final Map<String,Object> declaration = Map.of("schemaVersion",1,"enabled",true,"mimeTypes",List.of("text/plain"),"maxSources",0);
    @Test void readyRequiresExactLiveScopeAndRemovalRevokes() {
        var live = new AtomicBoolean(true);
        registry.register("session",scope,declaration,live::get);
        assertTrue(registry.ready(scope));
        for (var foreign : List.of(new Scope("other","client","owner","agent"),new Scope("tenant","other","owner","agent"),
                new Scope("tenant","client","other","agent"),new Scope("tenant","client","owner","other")))
            assertFalse(registry.ready(foreign));
        live.set(false); assertFalse(registry.ready(scope));
        live.set(true); registry.remove("session"); assertFalse(registry.ready(scope));
    }
    @Test void ambiguousSessionsFailClosedIncludingDisabledSecondSession() {
        registry.register("first",scope,declaration,()->true);
        registry.register("second",scope,null,()->true);
        assertFalse(registry.ready(scope));
        registry.remove("second"); assertTrue(registry.ready(scope));
        registry.register("first",scope,null,()->true); assertFalse(registry.ready(scope));
    }
    @Test void malformedDeclarationsAndFailedBindingCannotAdvertise() {
        for (Object bad : List.of(Map.of(),Map.of("schemaVersion",1,"enabled",true),
                Map.of("schemaVersion",1.1,"enabled",true,"mimeTypes",List.of("text/plain"),"maxSources",0),
                Map.of("schemaVersion",1,"enabled",true,"mimeTypes",List.of("image/png"),"maxSources",0),
                Map.of("schemaVersion",1,"enabled",true,"mimeTypes",List.of("text/plain"),"maxSources",1))) {
            registry.register("session",scope,bad,()->true); assertFalse(registry.ready(scope));
        }
        registry.register("session",scope,declaration,()->{throw new IllegalStateException();});
        assertFalse(registry.ready(scope));
    }
}
