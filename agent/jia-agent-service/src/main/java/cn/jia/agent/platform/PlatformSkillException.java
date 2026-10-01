package cn.jia.agent.platform;

public final class PlatformSkillException extends RuntimeException {
    private final int status;
    private final String code;
    public PlatformSkillException(int status,String code) { super(code); this.status=status; this.code=code; }
    public int status() { return status; }
    public String code() { return code; }
    static void require(boolean condition,int status,String code) { if (!condition) throw new PlatformSkillException(status,code); }
}
