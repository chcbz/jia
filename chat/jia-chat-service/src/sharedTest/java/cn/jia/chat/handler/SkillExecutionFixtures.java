package cn.jia.chat.handler;
import java.util.*;
final class SkillExecutionFixtures {
    static Map<String,Object> skill(String runner, String readiness) {
        var skill = new HashMap<String,Object>();
        skill.put("skillInstallationId","si_"+runner);skill.put("productVersionId","spv_"+runner);
        skill.put("kind","EXECUTABLE");skill.put("policyRevision","1");skill.put("enabled",true);skill.put("observedAt",123L);
        skill.put("skillKey", "image-edit"); skill.put("skillVersion", "1.0.0");
        skill.put("packageSha256", "a".repeat(64)); skill.put("manifestSha256", "b".repeat(64));
        skill.put("runner", runner); skill.put("readiness", readiness);
        skill.put("reasonCode", "READY".equals(readiness) ? null : "SKILL_RUNNER_MISSING");
        skill.put("toolchainDigest", "READY".equals(readiness) ? "c".repeat(64) : null);
        skill.put("bindingRevision", "provider-operation".equals(runner) && "READY".equals(readiness) ? "4" : null);
        return skill;
    }
    static Map<String,Object> catalog() {
        return new HashMap<>(Map.of("schemaVersion", 2, "catalogRevision", "7", "skills",
                List.of(skill("provider-operation", "READY"), withKey(skill("local-script", "DEPENDENCY_MISSING"), "pdf-export"))));
    }
    private static Map<String,Object> withKey(Map<String,Object> row, String key) { row.put("skillKey", key); return row; }

}
