package cn.jia.agent.common;

import java.util.Set;

public final class AgentSceneConstants {
    public static final String SCENE_JUYITING_MAIN = "juyiting-main";

    public static final String PHASE_ARRIVED = "arrived";
    public static final String PHASE_BLOCKED = "blocked";
    public static final Set<String> PHASES = Set.of(PHASE_ARRIVED, PHASE_BLOCKED);

    public static final String RESULT_ACCEPTED = "accepted";
    public static final String RESULT_IGNORED_STALE = "ignored_stale";
    public static final String RESULT_IGNORED_DUPLICATE = "ignored_duplicate";
    public static final Set<String> PHASE_RESULTS = Set.of(
            RESULT_ACCEPTED, RESULT_IGNORED_STALE, RESULT_IGNORED_DUPLICATE);

    private AgentSceneConstants() {
    }
}
