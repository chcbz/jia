package cn.jia.chat.service;

/** Current canonical JSON shares the skill encoder. Historical audit has its immutable v1 encoder. */
public final class CanonicalContextJson {
    private CanonicalContextJson() { }
    public static String write(Object value) { return cn.jia.agent.service.SkillCanonicalJson.write(value); }
}
