package cn.jia.chat.voice.validation;

import cn.jia.chat.voice.VoiceIdentity;
import cn.jia.chat.voice.api.VoiceErrorCode;
import cn.jia.chat.voice.api.VoiceException;
import cn.jia.chat.voice.config.VoiceSpeechProperties;
import cn.jia.chat.voice.state.VoiceDigests;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VoiceIdentityResolverTest {
    private final VoiceIdentityResolver resolver = new VoiceIdentityResolver();

    @AfterEach
    void clearContext() {
        EsContextHolder.clearContext();
    }

    @Test
    void acceptsAndScopesExactClaimBytesWithoutTrimmingOrNormalization() throws Exception {
        VoiceIdentity identity = resolver.resolve(jwt(Map.of(
                "jiacn", " tenant-é ",
                "client_id", " Client-A ",
                "sub", " user-17 ")));
        assertEquals(" tenant-é ", identity.jiacn());
        assertEquals(" Client-A ", identity.clientId());
        assertEquals(" user-17 ", identity.subject());

        VoiceSpeechProperties properties = new VoiceSpeechProperties();
        properties.setIdentityHmacSecret("01234567890123456789012345678901");
        String actual = new VoiceDigests(properties).identityScope(identity);
        assertEquals(expectedScope(properties.getIdentityHmacSecret(), identity), actual);
    }

    @Test
    void rejectsMissingNonStringUnicodeBlankAndUtf8ByteOverflowClaims() {
        assertUnauthorized(jwt(Map.of("jiacn", "tenant", "client_id", "client")));
        assertUnauthorized(jwt(Map.of("jiacn", "tenant", "client_id", 7, "sub", "subject")));
        assertUnauthorized(jwt(Map.of("jiacn", "\u00a0\u3000", "client_id", "client", "sub", "subject")));
        assertUnauthorized(jwt(Map.of("jiacn", "é".repeat(129), "client_id", "client", "sub", "subject")));
        assertUnauthorized(jwt(Map.of("jiacn", "tenant", "client_id", "é".repeat(129), "sub", "subject")));
        assertUnauthorized(jwt(Map.of("jiacn", "tenant", "client_id", "client", "sub", "é".repeat(257))));
    }


    @Test
    void rejectsLoneHighSurrogateBeforeIdentityHmac() {
        String malformed = "tenant-\uD83D";
        assertUnauthorized(jwt(Map.of(
                "jiacn", malformed, "client_id", "client", "sub", "subject")));
        assertDigestUnauthorized(new VoiceIdentity(malformed, "client", "subject"));
    }

    @Test
    void rejectsLoneLowSurrogateBeforeIdentityHmac() {
        String malformed = "tenant-\uDE00";
        assertUnauthorized(jwt(Map.of(
                "jiacn", "tenant", "client_id", malformed, "sub", "subject")));
        assertDigestUnauthorized(new VoiceIdentity("tenant", malformed, "subject"));
    }

    @Test
    void acceptsValidSurrogatePairAsExactIdentityBytes() throws Exception {
        VoiceIdentity identity = resolver.resolve(jwt(Map.of(
                "jiacn", "tenant-\uD83D\uDE00",
                "client_id", "client",
                "sub", "subject")));
        VoiceSpeechProperties properties = digestProperties();

        assertEquals("tenant-\uD83D\uDE00", identity.jiacn());
        assertEquals(expectedScope(properties.getIdentityHmacSecret(), identity),
                new VoiceDigests(properties).identityScope(identity));
    }

    @Test
    void acceptsExactUtf8ByteLimits() {
        VoiceIdentity identity = resolver.resolve(jwt(Map.of(
                "jiacn", "é".repeat(128),
                "client_id", "a".repeat(256),
                "sub", "é".repeat(256))));
        assertEquals(256, identity.jiacn().getBytes(StandardCharsets.UTF_8).length);
        assertEquals(256, identity.clientId().getBytes(StandardCharsets.UTF_8).length);
        assertEquals(512, identity.subject().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void neverUsesApiKeyOrThreadLocalFallback() {
        EsContext stale = new EsContext();
        stale.setJiacn("stale-tenant");
        stale.setClientId("stale-client");
        EsContextHolder.setContext(stale);
        UsernamePasswordAuthenticationToken apiKey = UsernamePasswordAuthenticationToken.authenticated(
                "api-key-principal", null, List.of());

        assertUnauthorized(apiKey);
        assertUnauthorized(null);
    }

    private void assertUnauthorized(org.springframework.security.core.Authentication authentication) {
        VoiceException error = assertThrows(VoiceException.class, () -> resolver.resolve(authentication));
        assertEquals(VoiceErrorCode.UNAUTHORIZED, error.error());
    }

    private static void assertDigestUnauthorized(VoiceIdentity identity) {
        VoiceException error = assertThrows(VoiceException.class,
                () -> new VoiceDigests(digestProperties()).identityScope(identity));
        assertEquals(VoiceErrorCode.UNAUTHORIZED, error.error());
    }

    private static VoiceSpeechProperties digestProperties() {
        VoiceSpeechProperties properties = new VoiceSpeechProperties();
        properties.setIdentityHmacSecret("01234567890123456789012345678901");
        return properties;
    }

    private static JwtAuthenticationToken jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("test")
                .header("alg", "none")
                .issuedAt(Instant.parse("2026-09-01T00:00:00Z"))
                .expiresAt(Instant.parse("2026-09-01T01:00:00Z"));
        claims.forEach(builder::claim);
        return new JwtAuthenticationToken(builder.build(), List.of());
    }

    private static String expectedScope(String secret, VoiceIdentity identity) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        for (String value : List.of(identity.jiacn(), identity.clientId(), identity.subject())) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            mac.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
            mac.update(bytes);
        }
        return HexFormat.of().formatHex(mac.doFinal());
    }
}
