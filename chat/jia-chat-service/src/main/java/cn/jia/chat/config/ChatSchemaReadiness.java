package cn.jia.chat.config;

import org.springframework.stereotype.Component;

/**
 * Explicit readiness boundary for consumers whose bean lifecycle precedes Spring Boot runners.
 * Successful source initializers are not repeated; failed or partial attempts remain retryable.
 */
@Component
public final class ChatSchemaReadiness {
    private final ChatSchemaInitializer chatSchema;
    private final ChatDeliberationSchemaInitializer deliberationSchema;
    private boolean initialized;

    public ChatSchemaReadiness(
            ChatSchemaInitializer chatSchema,
            ChatDeliberationSchemaInitializer deliberationSchema) {
        this.chatSchema = chatSchema;
        this.deliberationSchema = deliberationSchema;
    }

    public synchronized void ensureInitialized() throws Exception {
        if (initialized) return;
        chatSchema.ensureInitialized();
        deliberationSchema.ensureInitialized();
        initialized = true;
    }
}
