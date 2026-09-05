package cn.jia.economy.skill;

public enum SkillInstallationStatus {
    REQUESTED,
    INSTALLING,
    SUCCEEDED,
    FAILED;

    public boolean canTransitionTo(SkillInstallationStatus next) {
        return switch (this) {
            case REQUESTED -> next == INSTALLING || next == FAILED;
            case INSTALLING -> next == SUCCEEDED || next == FAILED;
            case SUCCEEDED, FAILED -> false;
        };
    }
}
