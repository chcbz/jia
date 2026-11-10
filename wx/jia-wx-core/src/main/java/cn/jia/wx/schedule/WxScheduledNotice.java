package cn.jia.wx.schedule;

import cn.jia.core.security.TenantClaimPolicy;
import java.time.LocalDate;
import java.util.Objects;

/** Immutable daily recipient/deadline facts, separate from task scheduling ownership. */
public record WxScheduledNotice(Key key, String jiacn, long dueAt, State state, String startToken) {
    public enum Kind { VOTE, PHRASE }
    public enum State { PLANNED, STARTED, SUCCEEDED, FAILED, UNKNOWN, SKIPPED, EXPIRED }

    public record Key(String clientId, LocalDate day, Kind kind, long subscriptionId) {
        public Key {
            TenantClaimPolicy.exact(clientId);
            Objects.requireNonNull(day, "day"); Objects.requireNonNull(kind, "kind");
            if (subscriptionId <= 0) throw new IllegalArgumentException("Persisted subscription required");
        }
        public String businessKey() { return day + ":" + kind + ":" + subscriptionId; }
        public static Key parse(String clientId, String businessKey) {
            String[] parts = Objects.requireNonNull(businessKey, "businessKey").split(":", -1);
            if (parts.length != 3) throw new IllegalArgumentException("Invalid daily notice key");
            Key key = new Key(clientId, LocalDate.parse(parts[0]), Kind.valueOf(parts[1]), Long.parseLong(parts[2]));
            if (!key.businessKey().equals(businessKey)) throw new IllegalArgumentException("Noncanonical daily notice key");
            return key;
        }
    }
}
