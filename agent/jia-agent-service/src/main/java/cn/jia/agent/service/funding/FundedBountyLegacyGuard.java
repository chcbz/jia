package cn.jia.agent.service.funding;

/** Always-on persisted guard for legacy task mutations, independent of preview service flags. */
public interface FundedBountyLegacyGuard {
    void requireAssignmentAllowed(String tenantId, String clientId, String taskId,
            boolean automatic, int targetCount, boolean taskRootLocked);

    void requireLifecycleAllowed(String tenantId, String clientId, String taskId,
            boolean taskRootLocked);

    /** Direct-construction compatibility only; Spring wiring replaces this with the persisted guard. */
    static FundedBountyLegacyGuard unconfigured() {
        return new FundedBountyLegacyGuard() {
            @Override
            public void requireAssignmentAllowed(String tenantId, String clientId, String taskId,
                    boolean automatic, int targetCount, boolean taskRootLocked) {
            }

            @Override
            public void requireLifecycleAllowed(String tenantId, String clientId, String taskId,
                    boolean taskRootLocked) {
            }
        };
    }

    static FundedBountyLegacyGuard failClosed() {
        return new FundedBountyLegacyGuard() {
            @Override
            public void requireAssignmentAllowed(String tenantId, String clientId, String taskId,
                    boolean automatic, int targetCount, boolean taskRootLocked) {
                throw unavailable();
            }

            @Override
            public void requireLifecycleAllowed(String tenantId, String clientId, String taskId,
                    boolean taskRootLocked) {
                throw unavailable();
            }

            private FundedBountyException unavailable() {
                return new FundedBountyException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "FUNDED_BOUNTY_GUARD_UNAVAILABLE",
                        "Persisted funded-task admission guard is unavailable", true);
            }
        };
    }
}
