package cn.jia.agent.config;

/** Hard network and admission bounds for privileged DLQ redrive. */
public record AgentCommandRedriveRabbitSettings(
        int connectionTimeoutMillis,
        int handshakeTimeoutMillis,
        int channelRpcTimeoutMillis,
        int shutdownTimeoutMillis,
        long controlCloseBudgetMillis,
        long maxConfirmTimeoutMillis,
        long publishAbortGraceMillis,
        int admissionTransactionTimeoutSeconds) {
    public static final int DEFAULT_CONNECTION_TIMEOUT_MILLIS = 2_000;
    public static final int DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 2_000;
    public static final int DEFAULT_CHANNEL_RPC_TIMEOUT_MILLIS = 2_000;
    public static final int DEFAULT_SHUTDOWN_TIMEOUT_MILLIS = 1_000;
    public static final long DEFAULT_CONTROL_CLOSE_BUDGET_MILLIS = 15_000L;
    public static final long MAX_REDRIVE_CONFIRM_TIMEOUT_MILLIS = 10_000L;
    public static final long DEFAULT_PUBLISH_ABORT_GRACE_MILLIS = 500L;

    public AgentCommandRedriveRabbitSettings(AgentCommandOperationsProperties properties) {
        this(DEFAULT_CONNECTION_TIMEOUT_MILLIS, DEFAULT_HANDSHAKE_TIMEOUT_MILLIS,
                DEFAULT_CHANNEL_RPC_TIMEOUT_MILLIS, DEFAULT_SHUTDOWN_TIMEOUT_MILLIS,
                DEFAULT_CONTROL_CLOSE_BUDGET_MILLIS, properties.confirmTimeoutMillis(),
                DEFAULT_PUBLISH_ABORT_GRACE_MILLIS,
                transactionTimeoutSeconds(properties.confirmTimeoutMillis()));
    }

    public AgentCommandRedriveRabbitSettings {
        if (connectionTimeoutMillis < 1 || handshakeTimeoutMillis < 1
                || channelRpcTimeoutMillis < 1 || shutdownTimeoutMillis < 1
                || controlCloseBudgetMillis < 1 || maxConfirmTimeoutMillis < 100
                || publishAbortGraceMillis < 1 || admissionTransactionTimeoutSeconds < 1
                || maxConfirmTimeoutMillis > MAX_REDRIVE_CONFIRM_TIMEOUT_MILLIS
                || connectionTimeoutMillis >= controlCloseBudgetMillis
                || handshakeTimeoutMillis >= controlCloseBudgetMillis
                || channelRpcTimeoutMillis >= controlCloseBudgetMillis
                || shutdownTimeoutMillis >= controlCloseBudgetMillis
                || maxConfirmTimeoutMillis + publishAbortGraceMillis
                        >= controlCloseBudgetMillis
                || admissionTransactionTimeoutSeconds * 1_000L
                        >= controlCloseBudgetMillis) {
            throw new IllegalStateException(
                    "Invalid D09 redrive Rabbit timeout budget");
        }
    }

    public long hardPublishTimeoutMillis(long confirmTimeoutMillis) {
        if (confirmTimeoutMillis < 100 || confirmTimeoutMillis > maxConfirmTimeoutMillis) {
            throw new IllegalArgumentException(
                    "redrive confirm timeout exceeds the dedicated connection budget");
        }
        return Math.addExact(confirmTimeoutMillis, publishAbortGraceMillis);
    }

    private static int transactionTimeoutSeconds(long confirmTimeoutMillis) {
        if (confirmTimeoutMillis < 100
                || confirmTimeoutMillis > MAX_REDRIVE_CONFIRM_TIMEOUT_MILLIS) {
            throw new IllegalStateException(
                    "Invalid D09 redrive Rabbit timeout budget");
        }
        long hardTimeout = Math.addExact(
                confirmTimeoutMillis, DEFAULT_PUBLISH_ABORT_GRACE_MILLIS);
        return Math.toIntExact(Math.floorDiv(hardTimeout + 999L, 1_000L) + 1L);
    }
}
