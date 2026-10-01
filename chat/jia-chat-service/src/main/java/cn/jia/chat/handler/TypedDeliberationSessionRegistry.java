package cn.jia.chat.handler;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Live authenticated-session index. Ambiguity is unavailable rather than an arbitrary winner. */
@Component
public final class TypedDeliberationSessionRegistry {
    public record Scope(String tenantId, String ownerJiacn, String clientId) { }
    public record Session(String sessionId, Scope scope, String agentId,
            TypedDeliberationDeclaration declaration, long registeredAt,
            BooleanSupplier currentBinding) { }
    public record Ready(String sessionId, String agentId, List<String> supportedOperations,
            Map<String, Object> frozenDeclaration) { }

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public void register(String sessionId, String tenantId, String ownerJiacn, String clientId,
            String agentId, Object rawDeclaration) {
        register(sessionId, tenantId, ownerJiacn, clientId, agentId, rawDeclaration, () -> true);
    }

    public void register(String sessionId, String tenantId, String ownerJiacn, String clientId,
            String agentId, Object rawDeclaration, BooleanSupplier currentBinding) {
        Session value = new Session(exact(sessionId, 100),
                new Scope(exact(tenantId, 50), exact(ownerJiacn, 50), exact(clientId, 50)),
                exact(agentId, 100), TypedDeliberationDeclaration.parse(rawDeclaration),
                System.currentTimeMillis(), Objects.requireNonNull(currentBinding, "currentBinding"));
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
            if (scope.equals(session.scope()) && target.equals(session.agentId()) && current(session)) {
                matching.add(session);
            }
        }
        matching.sort(Comparator.comparing(Session::sessionId));
        if (matching.size() != 1 || matching.getFirst().declaration().state()
                != TypedDeliberationDeclaration.State.READY) {
            throw new IllegalStateException("Typed deliberation runtime is unavailable");
        }
        Session selected = matching.getFirst();
        return new Ready(selected.sessionId(), selected.agentId(),
                selected.declaration().supportedOperations(), selected.declaration().frozenReceipt());
    }

    public int size() { return sessions.size(); }

    private static String key(String sessionId, String agentId) {
        return sessionId + "\0" + agentId;
    }

    private static boolean current(Session session) {
        try { return session.currentBinding().getAsBoolean(); }
        catch (RuntimeException unavailable) { return false; }
    }

    private static String exact(String value, int max) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > max
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid typed deliberation session identity");
        }
        return value;
    }
}
