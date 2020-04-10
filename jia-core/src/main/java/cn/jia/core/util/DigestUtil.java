package cn.jia.core.util;

import java.security.MessageDigest;

public class DigestUtil {
    private enum DigestType {

        MD5("MD5"), SHA("SHA"), SHA256("SHA-256"), SHA512("SHA-512");

        private String digestDesc;

        private DigestType(String digestDesc) {
            this.digestDesc = digestDesc;
        }

        public String getDigestDesc() {
            return digestDesc;
        }
    }

    private static String digest(String sourceStr, DigestType type) {
        char[] hexDigits = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'};
        try {
            byte[] btInput = sourceStr.getBytes();
            // 获得MD5摘要算法的 MessageDigest 对象
            MessageDigest mdInst = MessageDigest.getInstance(type.digestDesc);
            // 使用指定的字节更新摘要
            mdInst.update(btInput);
            // 获得密文
            byte[] md = mdInst.digest();
            // 把密文转换成十六进制的字符串形式
            int j = md.length;
            char[] str = new char[j * 2];
            int k = 0;
            for (byte byte0 : md) {
                str[k++] = hexDigits[byte0 >>> 4 & 0xf];
                str[k++] = hexDigits[byte0 & 0xf];
            }
            return new String(str);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static String MD5(String s) {
        return digest(s, DigestType.MD5);
    }

    public static String SHA(String s) {
        return digest(s, DigestType.SHA);
    }

    public static String SHA256(String s) {
        return digest(s, DigestType.SHA256);
    }

    public static String SHA512(String s) {
        return digest(s, DigestType.SHA512);
    }

    public static void main(String[] args) {
        String sourceStr1 = "123";
        String sourceStr2 = "Digest Str......";

        System.out.println(DigestUtil.MD5(sourceStr1));
        System.out.println(DigestUtil.MD5(sourceStr2));

        System.out.println(DigestUtil.SHA(sourceStr1));
        System.out.println(DigestUtil.SHA(sourceStr2));

        System.out.println(DigestUtil.SHA256(sourceStr1));
        System.out.println(DigestUtil.SHA256(sourceStr2));

        System.out.println(DigestUtil.SHA512(sourceStr1));
        System.out.println(DigestUtil.SHA512(sourceStr2));

    }
}
