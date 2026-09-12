package cn.jia.chat.archive.config;

import java.util.Objects;

/** Independent H05A feature gate layered over the frozen reader owner allowlist. */
public final class ArchiveQuestionAccessPolicy {
    private final boolean questionEnabled;
    private final ArchiveReaderAccessPolicy readerAccessPolicy;

    private ArchiveQuestionAccessPolicy(boolean questionEnabled,
                                        ArchiveReaderAccessPolicy readerAccessPolicy) {
        this.questionEnabled = questionEnabled;
        this.readerAccessPolicy = Objects.requireNonNull(readerAccessPolicy, "readerAccessPolicy");
    }

    public static ArchiveQuestionAccessPolicy from(ArchiveQuestionProperties properties,
                                                   ArchiveReaderAccessPolicy readerAccessPolicy) {
        Objects.requireNonNull(properties, "properties");
        return new ArchiveQuestionAccessPolicy(properties.isEnabled(), readerAccessPolicy);
    }

    public boolean enabled() {
        return questionEnabled && readerAccessPolicy.enabled();
    }

    public boolean allows(String tenantId, String clientId) {
        return questionEnabled && readerAccessPolicy.allows(tenantId, clientId);
    }
}
