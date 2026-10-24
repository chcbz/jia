package cn.jia.agent.entity;

import java.nio.charset.StandardCharsets;

final class RuntimeV2ExactIdentity {
    private RuntimeV2ExactIdentity() {}

    static void require(String value, int maximum, String name) {
        if (value == null || value.isBlank()
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is not exact");
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(++index))) {
                    throw new IllegalArgumentException(name + " has an incomplete Unicode character");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(name + " has an incomplete Unicode character");
            }
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > maximum) {
            throw new IllegalArgumentException(name + " exceeds its UTF-8 byte limit");
        }
    }
}
