package cn.jia.chat.handler;

import cn.jia.agent.service.SkillCapabilityLookup;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict runtime-fact projection. Malformed or absent replaces the old catalog with null;
 * no reported scope, entitlement, credential, cost or internal path is accepted. */
final class SkillExecutionDeclaration {
    private static final Set<String> CATALOG_FIELDS = Set.of("schemaVersion", "catalogRevision", "skills");
    private static final Set<String> SKILL_FIELDS = Set.of("skillKey", "skillVersion", "packageSha256",
            "manifestSha256", "runner", "readiness", "reasonCode", "toolchainDigest", "bindingRevision");

    static SkillCapabilityLookup.Catalog parse(Object value) {
        try {
            if (!(value instanceof Map<?, ?> catalog) || !catalog.keySet().equals(CATALOG_FIELDS)
                    || !versionOne(catalog.get("schemaVersion"))
                    || !(catalog.get("catalogRevision") instanceof String revision)
                    || !revision.matches("[1-9][0-9]{0,99}")
                    || !(catalog.get("skills") instanceof List<?> rows)) return null;
            var skills = new ArrayList<SkillCapabilityLookup.RuntimeSkill>();
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> skill) || !skill.keySet().equals(SKILL_FIELDS)) return null;
                String key = string(skill, "skillKey"), version = string(skill, "skillVersion");
                if (!key.matches("[a-z0-9][a-z0-9-]{0,63}")
                        || !version.matches("[0-9A-Za-z][0-9A-Za-z.+-]{0,63}")) return null;
                var readiness = SkillCapabilityLookup.Readiness.valueOf(string(skill, "readiness"));
                String reason = nullableString(skill, "reasonCode"), tool = nullableString(skill, "toolchainDigest"),
                        binding = nullableString(skill, "bindingRevision"), runner = string(skill, "runner");
                if (readiness == SkillCapabilityLookup.Readiness.READY
                        ? reason != null || tool == null || "provider-operation".equals(runner) && binding == null
                        : reason == null) return null;
                skills.add(new SkillCapabilityLookup.RuntimeSkill(key, version, string(skill, "packageSha256"),
                        string(skill, "manifestSha256"), runner, readiness, reason, tool, binding));
            }
            return new SkillCapabilityLookup.Catalog(revision, skills);
        } catch (RuntimeException invalid) { return null; }
    }

    private static boolean versionOne(Object value) {
        return value instanceof Number number && new BigDecimal(number.toString()).compareTo(BigDecimal.ONE) == 0;
    }
    private static String string(Map<?, ?> values, String key) {
        if (!(values.get(key) instanceof String text)) throw new IllegalArgumentException("Invalid skill declaration");
        return text;
    }
    private static String nullableString(Map<?, ?> values, String key) {
        return values.get(key) == null ? null : string(values, key);
    }
}
