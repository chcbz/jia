package cn.jia.agent.service;

import cn.jia.agent.entity.AgentInboxClaimToken;

import java.util.Arrays;
import java.util.Objects;

/**
 * External execution boundary for one durably claimed protocol-v2 command.
 *
 * <p>Implementations run outside database transactions. Any externally visible side effect
 * must be idempotent across process crashes, Rabbit redelivery, lease reclaim and a failed
 * durable completion. The stable business deduplication identity is tenant, data domain,
 * execution pool, command ID and delivery ID. Message ID and attempt are transport identities
 * and can change during reissue, so they must not cause the business side effect to run again.
 * For the same stable key, the Agent must durably retain the SHA-256 semantic fingerprint defined
 * by { AgentCommandV2RuntimeGateway#SEMANTIC_FINGERPRINT_CONTRACT}, return the prior outcome
 * when it matches, and reject semantic drift when it differs. A later Inbox attempt,
 * Rabbit redelivery, producer reissue or source-settlement retry is never authority to repeat
 * the side effect.</p>
 *
 * <p>W4 supplies the production runtime/WebSocket adapter; without an explicit implementation
 * the v2 Rabbit listener cannot be assembled.</p>
 */
public interface AgentCommandV2ExecutionPort {
    long SEND_TIMEOUT_MILLIS = 5_000L;

    Result execute(Command command);

    /** True only for an implementation that atomically settles the broker claim in its own transaction. */
    default boolean durableHandoff() { return false; }
    /** Revalidates the unique immutable persisted handoff on broker redelivery. No new dispatch is created. */
    default void verifyPrior(long inboxId) {}

    record Command(AgentInboxClaimToken claimToken, String ownerJiacn, byte[] wirePayload) {
        public Command {
            Objects.requireNonNull(claimToken, "claimToken");
            if (ownerJiacn == null || ownerJiacn.isEmpty() || ownerJiacn.length() > 50
                    || !ownerJiacn.equals(ownerJiacn.strip())
                    || ownerJiacn.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("ownerJiacn is not exact");
            }
            if (wirePayload == null || wirePayload.length == 0) {
                throw new IllegalArgumentException("wirePayload is required");
            }
            wirePayload = Arrays.copyOf(wirePayload, wirePayload.length);
        }

        @Override
        public byte[] wirePayload() {
            return Arrays.copyOf(wirePayload, wirePayload.length);
        }
    }

    record Result(Kind kind, long retryAfterMillis) {
        private static final long MAX_RETRY_AFTER_MILLIS = 300_000L;

        public Result {
            Objects.requireNonNull(kind, "kind");
            if (kind == Kind.RETRYABLE) {
                if (retryAfterMillis <= 0 || retryAfterMillis > MAX_RETRY_AFTER_MILLIS) {
                    throw new IllegalArgumentException("retryAfterMillis is out of range");
                }
            } else if (retryAfterMillis != 0) {
                throw new IllegalArgumentException("Only RETRYABLE carries a retry delay");
            }
        }

        public static Result dispatched() {
            return new Result(Kind.DISPATCHED, 0);
        }

        public static Result retryable(long retryAfterMillis) {
            return new Result(Kind.RETRYABLE, retryAfterMillis);
        }

        public static Result rejected() {
            return new Result(Kind.REJECTED, 0);
        }
    }

    enum Kind { DISPATCHED, RETRYABLE, REJECTED }
}
