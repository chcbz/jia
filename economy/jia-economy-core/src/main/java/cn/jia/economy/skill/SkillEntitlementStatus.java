package cn.jia.economy.skill;

public enum SkillEntitlementStatus {
    PENDING_INSTALLATION,
    ACTIVE,
    FAILED;

    public boolean canTransitionTo(SkillEntitlementStatus next) {
        return switch (this) {
            case PENDING_INSTALLATION -> next == ACTIVE || next == FAILED;
            case ACTIVE, FAILED -> false;
        };
    }
}
