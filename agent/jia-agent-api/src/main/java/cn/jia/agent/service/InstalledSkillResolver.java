package cn.jia.agent.service;

import java.util.Objects;
import java.util.regex.Pattern;

/** Read-only, source-neutral proof boundary for an exact installed skill. */
public interface InstalledSkillResolver {
    enum Origin { MARKET, PLATFORM_PROVISIONED }
    enum State { VERIFIED, PENDING, REVOKED, UNAVAILABLE }

    record Request(String tenant, String client, String owner, String canonicalAgent,
            long binding, Origin origin, String key, String version, String packageDigest) {
        private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");
        private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
        public Request {
            if (!"0".equals(tenant) || !exact(client, 50) || "0".equals(client)
                    || !exact(owner, 50) || "0".equals(owner)
                    || canonicalAgent == null || !ID.matcher(canonicalAgent).matches() || binding < 1
                    || origin == null || !exact(key, 64) || !exact(version, 64)
                    || !SHA.matcher(String.valueOf(packageDigest)).matches()) {
                throw new IllegalArgumentException("Invalid installed-skill resolution request");
            }
        }
    }

    record Proof(String installationRef, long revision, String key, String version,
            String packageDigest) {
        private static final Pattern REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");
        private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
        public Proof {
            if (installationRef == null || !REF.matcher(installationRef).matches() || revision < 1
                    || !exact(key, 64) || !exact(version, 64)
                    || !SHA.matcher(String.valueOf(packageDigest)).matches()) {
                throw new IllegalArgumentException("Invalid installed-skill proof");
            }
        }
    }

    record Resolution(State state, Proof proof) {
        public Resolution {
            Objects.requireNonNull(state, "state");
            if ((state == State.UNAVAILABLE) != (proof == null)) {
                throw new IllegalArgumentException("Unavailable resolutions must not expose a proof");
            }
        }
        public static Resolution unavailable() { return new Resolution(State.UNAVAILABLE, null); }
    }

    Resolution resolve(Request request);

    private static boolean exact(String value, int max) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.length() <= max
                && value.chars().noneMatch(c -> Character.isISOControl(c)
                        || Character.isSurrogate((char) c));
    }
}

