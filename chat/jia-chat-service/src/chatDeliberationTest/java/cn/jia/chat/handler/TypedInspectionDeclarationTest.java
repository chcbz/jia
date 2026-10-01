package cn.jia.chat.handler;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TypedInspectionDeclarationTest {
    static Map<String, Object> declaration(boolean enabled) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", 1);
        value.put("contract", TypedInspectionDeclaration.CONTRACT);
        value.put("enabled", enabled);
        value.put("profileId", "profile-1");
        value.put("engineContractId", "engine-1");
        value.put("enginePolicyDigest", digest('a'));
        value.put("toolPolicyDigest", digest('b'));
        value.put("inputPolicyDigest", digest('c'));
        value.put("toolPolicy", "STRICT_NO_TOOLS");
        value.put("recovery", TypedInspectionDeclaration.RECOVERY);
        value.put("supportedInputs", List.of(input("text", "text/plain", "DIRECT_TEXT", 'd')));
        return value;
    }

    static Map<String, Object> input(String media, String mime, String carrier, char digest) {
        return Map.of("mediaKind", media, "mimeType", mime, "carrier", carrier,
                "carrierContractDigest", digest(digest));
    }

    @Test
    void exactReadyDeclarationFreezesProfileAndInput() {
        Map<String, Object> raw = declaration(true);
        TypedInspectionDeclaration parsed = TypedInspectionDeclaration.parse(raw);
        assertEquals(TypedInspectionDeclaration.State.READY, parsed.state());
        assertEquals(raw, parsed.frozenReceipt());
        assertEquals("DIRECT_TEXT", parsed.requireInput("text", "text/plain").carrier());
        assertEquals(Map.of("profileId", "profile-1", "engineContractId", "engine-1",
                "enginePolicyDigest", digest('a'), "toolPolicyDigest", digest('b'),
                "inputPolicyDigest", digest('c')), parsed.manifestProfile());
    }

    @Test
    void disabledAndMalformedProfilesNeverBecomeReady() {
        assertEquals(TypedInspectionDeclaration.State.UNDECLARED,
                TypedInspectionDeclaration.parse(null).state());
        assertEquals(TypedInspectionDeclaration.State.DISABLED,
                TypedInspectionDeclaration.parse(declaration(false)).state());
        Map<String, Object> extra = declaration(true);
        extra.put("legacyInspect", true);
        assertEquals(TypedInspectionDeclaration.State.UNSUPPORTED,
                TypedInspectionDeclaration.parse(extra).state());
        Map<String, Object> mismatch = declaration(true);
        mismatch.put("supportedInputs", List.of(input("image", "image/png", "DIRECT_TEXT", 'd')));
        assertEquals(TypedInspectionDeclaration.State.UNSUPPORTED,
                TypedInspectionDeclaration.parse(mismatch).state());
        assertThrows(IllegalStateException.class,
                () -> TypedInspectionDeclaration.parse(declaration(false))
                        .requireInput("text", "text/plain"));
    }

    static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
