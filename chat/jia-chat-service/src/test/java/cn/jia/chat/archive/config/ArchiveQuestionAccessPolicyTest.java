package cn.jia.chat.archive.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
    void questionFlagCannotBypassReaderFeatureOrExactAllowlist() {
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
        assertFalse(enabled.allows("owner-a", "client-b"));
        assertFalse(enabled.allows("OWNER-A", "client-a"));
    }

    private ArchiveReaderAccessPolicy readerPolicy(boolean enabled) {
        ArchiveReaderProperties properties = new ArchiveReaderProperties();
        properties.setEnabled(enabled);
        if (enabled) {
            ArchiveReaderProperties.AllowedScope scope = new ArchiveReaderProperties.AllowedScope();
            scope.setTenantId("owner-a");
            scope.setClientId("client-a");
            properties.setAllowedScopes(new ArrayList<>(List.of(scope)));
        }
        return ArchiveReaderAccessPolicy.from(properties);
    }
}
