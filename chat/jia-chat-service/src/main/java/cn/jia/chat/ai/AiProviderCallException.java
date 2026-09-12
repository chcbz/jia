package cn.jia.chat.ai;

import java.io.Serial;
import java.util.Objects;

/** Provider-safe exception that never retains provider response text, prompts, credentials, or a cause chain. */
public final class AiProviderCallException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final AiFailureCategory category;
    private final boolean retryable;

    public AiProviderCallException(AiFailureCategory category) {
        super(Objects.requireNonNull(category, "category").safeMessage());
        this.category = category;
        this.retryable = false;
    }

    public AiFailureCategory category() {
        return category;
    }

    /** Fail closed: callers may not duplicate a potentially billable call automatically. */
    public boolean retryable() {
        return retryable;
    }
}
