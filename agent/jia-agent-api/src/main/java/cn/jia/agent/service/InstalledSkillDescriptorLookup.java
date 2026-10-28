package cn.jia.agent.service;

import java.util.List;
import java.util.Objects;

/** Authoritative installation facts; NOT task authorization or execution admission. */
public interface InstalledSkillDescriptorLookup {
    /** manifestJson is verified normalized metadata, not a Provider config or an executable command. */
    record Descriptor(String skillKey, String skillVersion, String installationId,
            String packageSha256, String manifestSha256, String manifestJson,
            String runtimeInstallationId, String runtimeHostId, String reasonCode) {
        public boolean verified() { return reasonCode == null && manifestJson != null; }
        /** Native execution also accepts a verified instruction package without a manifest. */
        public boolean verifiedNative() { return reasonCode == null && packageSha256 != null
                && ((manifestJson == null && manifestSha256 == null) || verified() && manifestSha256 != null); }
    }
    record Snapshot(List<Descriptor> skills) {
        public Snapshot { skills = List.copyOf(Objects.requireNonNull(skills)); }
    }
    /** Includes historical installations needed for exact task bindings and recovery. */
    Snapshot read(SkillCapabilityLookup.Scope scope);

    /** Current installations for profile display and new task selection, not task authorization. */
    default Snapshot readCurrent(SkillCapabilityLookup.Scope scope) { return read(scope); }

    final class SourceUnavailable extends RuntimeException {
        public enum Reason { UNAVAILABLE, FORBIDDEN }
        private final Reason reason;
        public SourceUnavailable() { this(Reason.UNAVAILABLE,null); }
        public SourceUnavailable(Throwable cause) { this(Reason.UNAVAILABLE,cause); }
        public SourceUnavailable(Reason reason,Throwable cause) {
            super("SKILL_DESCRIPTOR_SOURCE_UNAVAILABLE",cause);
            this.reason=Objects.requireNonNull(reason);
        }
        /** Internal classification; no change to the public descriptor/error envelope. */
        public Reason reason() { return reason; }
    }
}
