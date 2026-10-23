package cn.jia.agent.position;

/** Safe stable error; framework-to-HTTP mapping belongs to the controller. */
public final class PositionSkillException extends RuntimeException {
    private final int status;
    public PositionSkillException(int status, String code) { super(code); this.status = status; }
    public int status() { return status; }
    public static void require(boolean condition, int status, String code) {
        if (!condition) throw new PositionSkillException(status, code);
    }
}
