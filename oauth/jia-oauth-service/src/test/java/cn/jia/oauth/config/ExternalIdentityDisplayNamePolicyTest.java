package cn.jia.oauth.config;

import org.junit.jupiter.api.Test;

import static cn.jia.oauth.config.ExternalIdentityDisplayNamePolicy.Classification.*;
import static cn.jia.oauth.config.ExternalIdentityDisplayNamePolicy.Source.GITHUB;
import static org.junit.jupiter.api.Assertions.*;

class ExternalIdentityDisplayNamePolicyTest {
    private final ExternalIdentityDisplayNamePolicy policy = new ExternalIdentityDisplayNamePolicy();

    @Test
    void preservesChineseEmojiAndWesternAccentsForEveryNicknameProvider() {
        for (ExternalIdentityDisplayNamePolicy.Source source : ExternalIdentityDisplayNamePolicy.Source.values()) {
            assertAccepted(source, "陈惠超");
            assertAccepted(source, "陈惠超😀");
            assertAccepted(source, "José");
            assertAccepted(source, "Zoë");
            assertAccepted(source, "François");
        }
    }

    @Test
    void stripsUnicodeBoundarySpaceWithoutChangingInternalContent() {
        ExternalIdentityDisplayNamePolicy.Decision decision = policy.evaluate(GITHUB, "\u00A0 José 😀 \u3000");

        assertEquals(ACCEPTED, decision.classification());
        assertEquals("José 😀", decision.acceptedValue());
        assertFalse(decision.preserveExisting());
    }

    @Test
    void rejectsControlsColumnOverflowAndHighConfidenceRawMojibake() {
        assertRejected("safe\u0000name", CONTROL);
        assertRejected("陈".repeat(51), TOO_LONG);
        assertRejected("Ã©", SUSPECT_LEGACY_ENCODING);
        assertRejected("é\u0099\u0088æ\u0083\u00A0è¶\u0085", CONTROL);
    }

    @Test
    void emptyNicknamePreservesExistingValue() {
        ExternalIdentityDisplayNamePolicy.Decision decision = policy.evaluate(GITHUB, " \u00A0\u3000 ");

        assertEquals(EMPTY, decision.classification());
        assertNull(decision.acceptedValue());
        assertTrue(decision.preserveExisting());
        assertEquals(EMPTY, policy.evaluate(GITHUB, null).classification());
    }

    @Test
    void exactlyFiftyUnicodeCodePointsFitsNicknameColumn() {
        String value = "😀".repeat(50);

        assertEquals(50, value.codePointCount(0, value.length()));
        assertAccepted(value);
        assertRejected(value + "😀", TOO_LONG);
    }

    @Test
    void sourceIsMandatoryAndRetainedInDecision() {
        assertThrows(IllegalArgumentException.class, () -> policy.evaluate(null, "name"));
        assertEquals(GITHUB, policy.evaluate(GITHUB, "name").source());
    }

    private void assertAccepted(String value) {
        assertAccepted(GITHUB, value);
    }

    private void assertAccepted(ExternalIdentityDisplayNamePolicy.Source source, String value) {
        ExternalIdentityDisplayNamePolicy.Decision decision = policy.evaluate(source, value);
        assertEquals(ACCEPTED, decision.classification(), source + ":" + value);
        assertEquals(value, decision.acceptedValue());
        assertFalse(decision.preserveExisting());
    }

    private void assertRejected(String value, ExternalIdentityDisplayNamePolicy.Classification classification) {
        ExternalIdentityDisplayNamePolicy.Decision decision = policy.evaluate(GITHUB, value);
        assertEquals(classification, decision.classification(), value);
        assertNull(decision.acceptedValue());
        assertTrue(decision.preserveExisting());
    }
}
