package cn.jia.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordUtilTest {

    @Test
    void validatePassword() {
        String hash = PasswordUtil.encode("123");
        String hash1 = PasswordUtil.encode("123");
        System.out.println(hash);
        assertNotEquals(hash, hash1);
        assertTrue(PasswordUtil.validatePassword("123", hash));
        assertTrue(PasswordUtil.validatePassword("123", hash1));
    }
}