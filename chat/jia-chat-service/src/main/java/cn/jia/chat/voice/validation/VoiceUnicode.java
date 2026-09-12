package cn.jia.chat.voice.validation;

public final class VoiceUnicode {
    private VoiceUnicode() {
    }

    public static boolean isWellFormedUtf16(String value) {
        if (value == null) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index))) {
                    return false;
                }
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }
}
