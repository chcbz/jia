package cn.jia.agent.skill;

public final class SkillMarketplaceException extends RuntimeException {
    private final int status;
    private final String code;
    public SkillMarketplaceException(int status, String code) { super(code); this.status = status; this.code = code; }
    public int status() { return status; }
    public String code() { return code; }
    public static void require(boolean condition, int status, String code) {
        if (!condition) throw new SkillMarketplaceException(status, code);
    }
}
