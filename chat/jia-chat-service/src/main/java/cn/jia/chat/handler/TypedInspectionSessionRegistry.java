package cn.jia.chat.handler;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Live exact-scope INSPECT profile index; ambiguity and stale bindings are unavailable. */
@Component
public final class TypedInspectionSessionRegistry {
    public record Scope(String tenantId, String ownerJiacn, String clientId) { }
    public record Session(String sessionId, Scope scope, String agentId,
            TypedInspectionDeclaration declaration, BooleanSupplier currentBinding) { }
    public record Ready(String sessionId, String agentId, Map<String, Object> frozenDeclaration,
            Map<String, Object> manifestProfile, TypedInspectionDeclaration declaration) { }

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public void register(String sessionId, String tenantId, String ownerJiacn, String clientId,
            String agentId, Object rawDeclaration, BooleanSupplier currentBinding) {
        Session value = new Session(exact(sessionId, 128),
                new Scope(exact(tenantId, 50), exact(ownerJiacn, 50), exact(clientId, 50)),
                exact(agentId, 100), TypedInspectionDeclaration.parse(rawDeclaration),
                Objects.requireNonNull(currentBinding, "currentBinding"));
        sessions.put(key(value.sessionId(), value.agentId()), value);
    }

    public void remove(String sessionId) {
        if (sessionId != null) sessions.entrySet().removeIf(entry -> sessionId.equals(entry.getValue().sessionId()));
    }

    public Ready requireSingleReady(Scope scope, String agentId) {
        Objects.requireNonNull(scope, "scope");
        String target = exact(agentId, 100);
        List<Session> matching = new ArrayList<>();
        for (Session session : sessions.values()) {
            if (scope.equals(session.scope()) && target.equals(session.agentId()) && current(session)) matching.add(session);
        }
        matching.sort(Comparator.comparing(Session::sessionId));
        if (matching.size() != 1 || matching.getFirst().declaration().state()
                != TypedInspectionDeclaration.State.READY) {
            throw new IllegalStateException("Typed inspection runtime is unavailable");
        }
        Session selected = matching.getFirst();
        return new Ready(selected.sessionId(), selected.agentId(), selected.declaration().frozenReceipt(),
                selected.declaration().manifestProfile(), selected.declaration());
    }

    public int size() { return sessions.size(); }
    private static String key(String sessionId, String agentId) { return sessionId + "\0" + agentId; }
    private static boolean current(Session session) {
        try { return session.currentBinding().getAsBoolean(); } catch (RuntimeException unavailable) { return false; }
    }
    private static String exact(String value, int max) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > max
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid typed inspection session identity");
        }
        return value;
    }
}
