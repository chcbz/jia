package cn.jia.economy.skill;

public enum SkillOrderStatus {
    FUNDS_HELD,
    INSTALLING,
    ACTIVE,
    REFUNDED;

    public boolean canTransitionTo(SkillOrderStatus next) {
        return switch (this) {
            case FUNDS_HELD -> next == INSTALLING || next == REFUNDED;
            case INSTALLING -> next == ACTIVE || next == REFUNDED;
            case ACTIVE, REFUNDED -> false;
        };
    }
}
