package cn.jia.agent.output;

/** One fail-closed source handler is registered for each source type. */
public interface OutputSourceAuthorizer {
    String sourceType();

    /**
     * Re-reads the exact business authorization while holding its canonical source-root locks.
     * Implementations must acquire all source-specific locks before returning.
     */
    OutputSourceAuthorization lockAndAuthorize(
            String tenantId, String clientId, String sourceId, String producerAgentId);

    /**
     * Locks and authorizes one explicit access mode. Handlers must opt in to historical
     * receipt reads; the default remains fail-closed for every non-mutation mode.
     */
    default OutputSourceAuthorization lockAndAuthorize(
            String tenantId, String clientId, String sourceId, String producerAgentId,
            OutputSourceAccessMode accessMode) {
        if (accessMode != OutputSourceAccessMode.MUTATION) {
            throw new OutputAuthorizationException("OUTPUT_SOURCE_FORBIDDEN",
                    "Output source does not support receipt read authorization");
        }
        return lockAndAuthorize(tenantId, clientId, sourceId, producerAgentId);
    }
}
