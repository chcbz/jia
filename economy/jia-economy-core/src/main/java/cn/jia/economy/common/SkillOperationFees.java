package cn.jia.economy.common;

/** Fixed WEN operation charges. Package licensing does not waive installation/upgrade service fees. */
public final class SkillOperationFees {
    public static final long INSTALL_WEN = 50;
    public static final long UPGRADE_WEN = 20;
    private SkillOperationFees() { }
    public static long forAction(String action) {
        return switch (action) {
            case "INSTALL" -> INSTALL_WEN;
            case "UPGRADE" -> UPGRADE_WEN;
            default -> throw new IllegalArgumentException("Not a priced skill operation: " + action);
        };
    }
}
