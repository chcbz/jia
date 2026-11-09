package cn.jia.task.scheduling;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable state for one coalesced business wakeup. Persistence must compare-and-set
 * version; business effects still require their own idempotency/authorization.
 * A scheduler lease fences result writes, not an already running external operation.
 */
public record ScheduledWork(long version, long requestedRevision, Long dueAt, Claim claim) {
    public record Claim(String token, long revision, long expiresAt) { }
    public record Acquisition(ScheduledWork work, Optional<Claim> acquired) { }

    public static ScheduledWork empty() {
        return new ScheduledWork(0, 0, null, null);
    }

    /** Merge repeated notifications; earlier pending work must not be postponed. */
    public ScheduledWork signal(long at) {
        return changed(Math.incrementExact(requestedRevision),
                dueAt == null ? at : Math.min(dueAt, at), claim);
    }

    /** Replace a business deadline, preserving any in-flight execution fence. */
    public ScheduledWork reschedule(long at) {
        return changed(Math.incrementExact(requestedRevision), at, claim);
    }

    /** Stop future work. An in-flight callback is not forcibly interrupted. */
    public ScheduledWork cancel() {
        if (dueAt == null && (claim == null || requestedRevision > claim.revision())) return this;
        return changed(Math.incrementExact(requestedRevision), null, claim);
    }

    /** Indexed persisted wake time: an in-flight claim owns the key until settled/expired. */
    public Long nextWakeAt() {
        return claim == null ? dueAt : claim.expiresAt();
    }

    /**
     * Called under a short persistence transaction. Persist work even if acquired is
     * empty: expiration may retire a canceled claim or restore a future deadline.
     */
    public Acquisition acquire(long now, long expiresAt, String token) {
        if (expiresAt <= now) throw new IllegalArgumentException("Lease must expire after acquisition");
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Claim token is required");
        if (claim != null && now < claim.expiresAt()) return notAcquired(this);
        if (claim != null && token.equals(claim.token()))
            throw new IllegalArgumentException("A replacement claim needs a fresh token");
        Long readyAt = dueAt;
        if (claim != null && requestedRevision == claim.revision()) {
            // Expired unfinished work retries the same logical revision, not a new charge.
            readyAt = now;
        }
        if (readyAt == null || readyAt > now) {
            return notAcquired(claim == null ? this : changed(requestedRevision, readyAt, null));
        }
        var acquired = new Claim(token, requestedRevision, expiresAt);
        return new Acquisition(changed(requestedRevision, null, acquired), Optional.of(acquired));
    }

    /** A notification received during execution remains due after successful completion. */
    public Optional<ScheduledWork> succeed(Claim expected, long now) {
        return owns(expected, now) ? Optional.of(changed(requestedRevision, dueAt, null)) : Optional.empty();
    }

    /** Explicit retry of a failed callback; a newer deadline/cancellation wins over it. */
    public Optional<ScheduledWork> retry(Claim expected, long now, long retryAt) {
        if (!owns(expected, now)) return Optional.empty();
        if (retryAt < now) throw new IllegalArgumentException("Retry cannot precede settlement");
        Long next = requestedRevision == claim.revision() ? retryAt : dueAt;
        return Optional.of(changed(requestedRevision, next, null));
    }

    public Optional<ScheduledWork> renew(Claim expected, long now, long expiresAt) {
        if (!owns(expected, now)) return Optional.empty();
        if (expiresAt <= claim.expiresAt()) throw new IllegalArgumentException("Renewal must extend the lease");
        return Optional.of(changed(requestedRevision, dueAt,
                new Claim(claim.token(), claim.revision(), expiresAt)));
    }

    private boolean owns(Claim expected, long now) {
        // The caller retains token/revision; renewal changes expiresAt, not ownership.
        return claim != null && expected != null && claim.revision() == expected.revision()
                && Objects.equals(claim.token(), expected.token()) && now < claim.expiresAt();
    }

    private ScheduledWork changed(long revision, Long next, Claim owner) {
        return new ScheduledWork(Math.incrementExact(version), revision, next, owner);
    }

    private static Acquisition notAcquired(ScheduledWork work) {
        return new Acquisition(work, Optional.empty());
    }
}
