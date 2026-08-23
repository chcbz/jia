package cn.jia.agent.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes snapshot query and publication per exact Agent owner scope. */
@Component
public class AgentScopePublicationCoordinator {
    private final ConcurrentHashMap<ScopeKey, ReentrantLock> locks = new ConcurrentHashMap<>();

    public void execute(String clientId, String ownerJiacn, Runnable publication) {
        ScopeKey scope = new ScopeKey(
                requireExact(clientId, "clientId"),
                requireExact(ownerJiacn, "ownerJiacn"));
        ReentrantLock lock = locks.computeIfAbsent(scope, ignored -> new ReentrantLock(true));
        lock.lock();
        try {
            publication.run();
        } finally {
            lock.unlock();
        }
    }

    private String requireExact(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private record ScopeKey(String clientId, String ownerJiacn) {
    }
}
