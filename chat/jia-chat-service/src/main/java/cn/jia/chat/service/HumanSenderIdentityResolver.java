package cn.jia.chat.service;

import cn.jia.core.context.EsContext;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Resolves human chat identity exclusively from authenticated server state. */
@Service
@RequiredArgsConstructor
public class HumanSenderIdentityResolver {
    private static final int MAX_SENDER_NAME_CODE_POINTS = 100;
    private static final String FALLBACK_DISPLAY_NAME = "用户";

    private final UserService userService;

    public ServerResolvedSender resolve(EsContext context) {
        if (context == null) {
            throw unavailableIdentity();
        }
        String jiacn = requireExactIdentity(context.getJiacn());
        String clientId = requireExactIdentity(context.getClientId());

        UserEntity user = userService.findByJiacn(jiacn);
        if (user != null && user.getJiacn() != null && !jiacn.equals(user.getJiacn())) {
            user = null;
        }

        String displayName = user == null ? null : usableDisplayName(user.getNickname(), true);
        if (displayName != null) {
            return resolved(displayName, jiacn, clientId, DisplayNameSource.NICKNAME);
        }
        displayName = user == null ? null : usableDisplayName(user.getUsername(), false);
        if (displayName != null) {
            return resolved(displayName, jiacn, clientId, DisplayNameSource.USERNAME);
        }
        displayName = usableDisplayName(context.getUsername(), false);
        if (displayName != null) {
            return resolved(displayName, jiacn, clientId, DisplayNameSource.CONTEXT_USERNAME);
        }
        displayName = usableDisplayName(jiacn, false);
        if (displayName != null) {
            return resolved(displayName, jiacn, clientId, DisplayNameSource.JIACN);
        }
        return resolved(FALLBACK_DISPLAY_NAME, jiacn, clientId, DisplayNameSource.FALLBACK);
    }

    private ServerResolvedSender resolved(
            String displayName, String jiacn, String clientId, DisplayNameSource source) {
        return new ServerResolvedSender(
                ServerResolvedSender.USER_TYPE, displayName, jiacn, clientId, source);
    }

    private String usableDisplayName(String raw, boolean allowLegacyRepair) {
        if (raw == null) {
            return null;
        }
        String candidate = raw.strip();
        if (candidate.isEmpty()) {
            return null;
        }

        if (allowLegacyRepair) {
            LegacyRepair repair = repairLegacyRawUtf8(candidate);
            if (repair.classification == LegacyClassification.REPAIRED) {
                candidate = repair.value;
            } else if (repair.classification == LegacyClassification.SUSPECT_IRREVERSIBLE) {
                return null;
            }
        }
        return canonicalDisplayName(candidate) ? candidate : null;
    }

    private LegacyRepair repairLegacyRawUtf8(String candidate) {
        if (!candidate.chars().allMatch(codePoint -> codePoint <= 0xFF)
                || !containsUtf8LeadContinuation(candidate)) {
            return LegacyRepair.notLegacy();
        }

        byte[] bytes = new byte[candidate.length()];
        for (int i = 0; i < candidate.length(); i++) {
            bytes[i] = (byte) candidate.charAt(i);
        }
        try {
            CharBuffer decodedBuffer = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            String decoded = decodedBuffer.toString();
            if (!canonicalDisplayName(decoded)
                    || !Arrays.equals(bytes, decoded.getBytes(StandardCharsets.UTF_8))) {
                return LegacyRepair.suspect();
            }
            return LegacyRepair.repaired(decoded);
        } catch (CharacterCodingException invalidUtf8) {
            return LegacyRepair.suspect();
        }
    }

    private boolean containsUtf8LeadContinuation(String value) {
        for (int i = 0; i + 1 < value.length(); i++) {
            int lead = value.charAt(i);
            int next = value.charAt(i + 1);
            if (lead >= 0xC2 && lead <= 0xF4 && next >= 0x80 && next <= 0xBF) {
                return true;
            }
        }
        return false;
    }

    private boolean canonicalDisplayName(String value) {
        return value != null
                && !value.isBlank()
                && value.equals(value.strip())
                && value.codePointCount(0, value.length()) <= MAX_SENDER_NAME_CODE_POINTS
                && value.codePoints().noneMatch(codePoint -> Character.isISOControl(codePoint)
                || codePoint == 0xFFFD);
    }

    private String requireExactIdentity(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw unavailableIdentity();
        }
        return value;
    }

    private IllegalStateException unavailableIdentity() {
        return new IllegalStateException("Authenticated chat identity is unavailable");
    }

    private enum LegacyClassification {
        NOT_LEGACY,
        REPAIRED,
        SUSPECT_IRREVERSIBLE
    }

    private record LegacyRepair(LegacyClassification classification, String value) {
        private static LegacyRepair notLegacy() {
            return new LegacyRepair(LegacyClassification.NOT_LEGACY, null);
        }

        private static LegacyRepair repaired(String value) {
            return new LegacyRepair(LegacyClassification.REPAIRED, value);
        }

        private static LegacyRepair suspect() {
            return new LegacyRepair(LegacyClassification.SUSPECT_IRREVERSIBLE, null);
        }
    }
}
