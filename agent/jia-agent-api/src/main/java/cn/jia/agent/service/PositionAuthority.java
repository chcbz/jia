package cn.jia.agent.service;

import java.util.function.Supplier;

/** Trusted scope comes only from the authenticated boundary. Caller must also hold the Runtime native fence. */
public interface PositionAuthority {
    record Scope(String tenantId, String clientId, String ownerJiacn) { }
    record Grant(String appointmentId, long appointmentRevision, long positionRevision,
            String agentId, long bindingId, String workId, boolean publish) { }
    /** Runs the complete business commit while holding the same persistent root used by resignation. */
    <T> T withAuthority(Scope scope, Grant grant, Supplier<T> commit);
}
