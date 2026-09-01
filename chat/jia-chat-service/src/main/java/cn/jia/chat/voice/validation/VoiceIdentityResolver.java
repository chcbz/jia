package cn.jia.chat.voice.validation;

import cn.jia.chat.voice.VoiceIdentity;
import cn.jia.chat.voice.api.VoiceErrorCode;
import cn.jia.chat.voice.api.VoiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class VoiceIdentityResolver {
    static final int JIACN_MAX_BYTES = 256;
    static final int CLIENT_ID_MAX_BYTES = 256;
    static final int SUBJECT_MAX_BYTES = 512;

    public VoiceIdentity resolve(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
                || !authentication.isAuthenticated()) {
            throw VoiceException.of(VoiceErrorCode.UNAUTHORIZED, null);
        }
        Map<String, Object> claims = jwtAuthentication.getToken().getClaims();
        return new VoiceIdentity(
                requiredExact(claims, "jiacn", JIACN_MAX_BYTES),
                requiredExact(claims, "client_id", CLIENT_ID_MAX_BYTES),
                requiredExact(claims, "sub", SUBJECT_MAX_BYTES));
    }

    private static String requiredExact(Map<String, Object> claims, String name, int maxBytes) {
        Object raw = claims.get(name);
        if (!(raw instanceof String value)
                || value.getBytes(StandardCharsets.UTF_8).length > maxBytes
                || value.codePoints().allMatch(VoiceIdentityResolver::isUnicodeWhitespace)) {
            throw VoiceException.of(VoiceErrorCode.UNAUTHORIZED, null);
        }
        return value;
    }

    static boolean isUnicodeWhitespace(int codePoint) {
        return codePoint >= 0x0009 && codePoint <= 0x000D
                || codePoint == 0x0020 || codePoint == 0x0085 || codePoint == 0x00A0
                || codePoint == 0x1680 || codePoint >= 0x2000 && codePoint <= 0x200A
                || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202F
                || codePoint == 0x205F || codePoint == 0x3000;
    }
}
