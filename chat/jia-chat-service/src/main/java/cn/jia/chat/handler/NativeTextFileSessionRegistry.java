package cn.jia.chat.handler;

import cn.jia.agent.service.NativeTextFileRuntimeLookup;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Only an exact, live installation-derived WS binding can advertise this executor. */
@Component
public final class NativeTextFileSessionRegistry implements NativeTextFileRuntimeLookup {
    private record Entry(String sessionId, Scope scope, boolean enabled, BooleanSupplier current) { }
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    public void register(String sessionId, Scope scope, Object declaration, BooleanSupplier current) {
        Objects.requireNonNull(scope); Objects.requireNonNull(current);
        for (String value : List.of(sessionId, scope.tenantId(), scope.clientId(), scope.ownerJiacn(), scope.agentId()))
            if (value.isBlank() || !value.equals(value.strip())) throw new IllegalArgumentException("Invalid native text scope");
        boolean enabled = declaration instanceof Map<?,?> m
                && m.keySet().equals(java.util.Set.of("schemaVersion", "enabled", "mimeTypes", "maxSources"))
                && m.get("schemaVersion") instanceof Number v && v.doubleValue() == 1
                && Boolean.TRUE.equals(m.get("enabled")) && List.of("text/plain").equals(m.get("mimeTypes"))
                && m.get("maxSources") instanceof Number n && n.doubleValue() == 0;
        entries.put(sessionId + "\0" + scope.agentId(), new Entry(sessionId, scope, enabled, current));
    }
    public void remove(String sessionId) { entries.values().removeIf(e -> e.sessionId().equals(sessionId)); }
    @Override public boolean ready(Scope scope) {
        Entry selected = null;
        for (Entry entry : entries.values()) {
            if (!entry.scope().equals(scope)) continue;
            boolean live;
            try { live = entry.current().getAsBoolean(); } catch (RuntimeException unavailable) { live = false; }
            if (!live) continue;
            if (selected != null) return false;
            selected = entry;
        }
        return selected != null && selected.enabled();
    }
}
