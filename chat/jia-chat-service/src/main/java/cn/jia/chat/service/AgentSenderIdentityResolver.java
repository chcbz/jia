package cn.jia.chat.service;

import cn.jia.agent.entity.AgentRuntimeDTO;

/** Shared server-authoritative Agent display-name rule. */
public final class AgentSenderIdentityResolver {
    private static final int MAX_SENDER_NAME_LENGTH = 100;

    private AgentSenderIdentityResolver() {
    }

    public static String resolve(AgentRuntimeDTO runtime, String authenticatedAgentId) {
        if (!canonicalId(authenticatedAgentId)) {
            throw new IllegalArgumentException("Authenticated agent identity is unavailable");
        }
        if (runtime == null || !authenticatedAgentId.equals(runtime.getAgentId())) {
            return authenticatedAgentId;
        }
        String candidate = runtime.getName();
        if (!canonicalDisplayName(candidate)) {
            candidate = runtime.getPersonaName();
        }
        return canonicalDisplayName(candidate) ? candidate : authenticatedAgentId;
    }

    static boolean canonicalDisplayName(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.length() <= MAX_SENDER_NAME_LENGTH
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static boolean canonicalId(String value) {
        return canonicalDisplayName(value);
    }
}
