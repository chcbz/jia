package cn.jia.chat.archive.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveQuestionAccessPolicyTest {
    @Test
    void questionFlagIsIndependentlyDefaultOffWithoutChangingReaderAccess() {
        ArchiveReaderAccessPolicy reader = readerPolicy(true);
        ArchiveQuestionAccessPolicy questions = ArchiveQuestionAccessPolicy.from(
                new ArchiveQuestionProperties(), reader);

        assertTrue(reader.allows("owner-a", "client-a"));
        assertFalse(questions.enabled());
        assertFalse(questions.allows("owner-a", "client-a"));
    }

    @Test
    void questionFlagCannotBypassReaderFeatureAndRequiresValidAuthenticationClaims() {
        ArchiveQuestionProperties properties = new ArchiveQuestionProperties();
        properties.setEnabled(true);

        ArchiveQuestionAccessPolicy readerOff = ArchiveQuestionAccessPolicy.from(
                properties, readerPolicy(false));
        assertFalse(readerOff.enabled());
        assertFalse(readerOff.allows("owner-a", "client-a"));

        ArchiveQuestionAccessPolicy enabled = ArchiveQuestionAccessPolicy.from(
                properties, readerPolicy(true));
        assertTrue(enabled.enabled());
        assertTrue(enabled.allows("owner-a", "client-a"));
        assertTrue(enabled.allows("owner-a", "client-b"));
        assertFalse(enabled.allows("owner-a", "client-a\n"));
    }

    private ArchiveReaderAccessPolicy readerPolicy(boolean enabled) {
        ArchiveReaderProperties properties = new ArchiveReaderProperties();
        properties.setEnabled(enabled);
        return ArchiveReaderAccessPolicy.from(properties);
    }
}
