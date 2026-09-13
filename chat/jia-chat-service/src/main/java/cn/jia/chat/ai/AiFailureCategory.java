package cn.jia.chat.ai;

/** Finite, safe classifications for provider failures. */
public enum AiFailureCategory {
    DISABLED("External AI is disabled"),
    CONNECT_TIMEOUT("External AI connection timed out"),
    FIRST_TOKEN_TIMEOUT("External AI first token timed out"),
    TOTAL_TIMEOUT("External AI call timed out"),
    AUTHENTICATION("External AI authentication failed"),
    RATE_LIMITED("External AI rate limit was reached"),
    PROVIDER_REJECTED("External AI rejected the request"),
    PROVIDER_UNAVAILABLE("External AI is unavailable"),
    CANCELLED("External AI call was cancelled"),
    UNKNOWN("External AI call failed");

    private final String safeMessage;

    AiFailureCategory(String safeMessage) {
        this.safeMessage = safeMessage;
    }

    public String safeMessage() {
        return safeMessage;
    }
}
