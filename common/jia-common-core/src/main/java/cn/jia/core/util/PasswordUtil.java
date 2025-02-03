package cn.jia.core.util;

import jodd.util.BCrypt;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;

/**
 * 密码生成和验证工具类

 * @author chc
 **/
@Slf4j
public class PasswordUtil {
    private static final Pattern BCRYPT_PATTERN = Pattern.compile("\\A\\$2(a|y|b)?\\$(\\d\\d)\\$[./0-9A-Za-z]{53}");

    private static final int STRENGTH = 7;

    /**
     * 加密密码
     *
     * @param rawPassword 原密码
     * @return 加密后密码
     */
    public static String encode(CharSequence rawPassword) {
        if (rawPassword == null) {
            throw new IllegalArgumentException("rawPassword cannot be null");
        }
        String salt = createSalt();
        return BCrypt.hashpw(rawPassword.toString(), salt);
    }

    /**
     * 生成盐值
     *
     * @return 盐值
     */
    public static String createSalt() {
        return BCrypt.gensalt(STRENGTH);
    }

    /**
     * 校验密码
     *
     * @param rawPassword 原密码
     * @param encodedPassword 加密后密码
     * @return 校验结果
     */
    public static boolean validatePassword(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null) {
            throw new IllegalArgumentException("rawPassword cannot be null");
        }
        if (StringUtil.isEmpty(encodedPassword)) {
            log.warn("Empty encoded password");
            return false;
        }
        if (!BCRYPT_PATTERN.matcher(encodedPassword).matches()) {
            log.warn("Encoded password does not look like BCrypt");
            return false;
        }
        return BCrypt.checkpw(rawPassword.toString(), encodedPassword);
    }
}