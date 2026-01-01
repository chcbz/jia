package cn.jia.core.util;

import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 字符串工具类。
 *
 * @author carver.gu
 * @since 1.0, Sep 12, 2009
 */
public class StringUtil {
    /**
     * 检查指定的字符串是否为空。
     * <ul>
     * <li>SysUtils.isEmpty(null) = true</li>
     * <li>SysUtils.isEmpty("") = true</li>
     * <li>SysUtils.isEmpty("   ") = true</li>
     * <li>SysUtils.isEmpty("abc") = false</li>
     * </ul>
     *
     * @param value 待检查的字符串
     * @return true/false
     */
    public static boolean isBlank(String value) {
        int strLen;
        if (value == null || (strLen = value.length()) == 0) {
            return true;
        }
        for (int i = 0; i < strLen; i++) {
            if ((!Character.isWhitespace(value.charAt(i)))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isNotBlank(String str) {
        return !isEmpty(str);
    }

    public static boolean isEmpty(final CharSequence cs) {
        return cs == null || cs.isEmpty();
    }

    public static boolean isNotEmpty(final CharSequence cs) {
        return !StringUtil.isEmpty(cs);
    }

    /**
     * 检查对象是否为数字型字符串,包含负数开头的。
     */
    public static boolean isNumeric(Object obj) {
        if (obj == null) {
            return false;
        }
        char[] chars = obj.toString().toCharArray();
        int length = chars.length;
        if (length < 1) {
            return false;
        }

        int i = 0;
        if (length > 1 && chars[0] == '-') {
            i = 1;
        }

        for (; i < length; i++) {
            if (!Character.isDigit(chars[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查指定的字符串列表是否不为空。
     */
    public static boolean areNotEmpty(String... values) {
        boolean result = true;
        if (values == null || values.length == 0) {
            result = false;
        } else {
            for (String value : values) {
                result &= !isEmpty(value);
            }
        }
        return result;
    }

    /**
     * 把通用字符编码的字符串转化为汉字编码。
     */
    public static String unicodeToChinese(String unicode) {
        StringBuilder out = new StringBuilder();
        if (!isEmpty(unicode)) {
            for (int i = 0; i < unicode.length(); i++) {
                out.append(unicode.charAt(i));
            }
        }
        return out.toString();
    }

    /**
     * 过滤不可见字符
     */
    public static String stripNonValidXmlCharacters(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        char current;
        for (int i = 0; i < input.length(); i++) {
            current = input.charAt(i);
            if (current == 0x9 || current == 0xA || current == 0xD || (current >= 0x20 && current <= 0xD7FF) ||
                    (current >= 0xE000 && current <= 0xFFFD)) {
                out.append(current);
            }
        }
        return out.toString();
    }

    public static boolean equals(String obj1, String obj2) {
        return Objects.equals(obj1, obj2);
    }

    public static String asciiToString(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        StringBuilder sbu = new StringBuilder();
        String[] chars = value.split(",");
        for (String aChar : chars) {
            try {
                sbu.append((char) Integer.parseInt(aChar.trim()));
            } catch (NumberFormatException e) {
                // 忽略无效的ASCII值
                continue;
            }
        }
        return sbu.toString();
    }

    public static String stringToAscii(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder sbu = new StringBuilder();
        char[] chars = value.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (i != chars.length - 1) {
                sbu.append((int) chars[i]).append(",");
            } else {
                sbu.append((int) chars[i]);
            }
        }
        return sbu.toString();
    }

    /**
     * 将null转空字符
     *
     * @param value 原字符串
     * @return 转换后的字符串
     */
    public static String ignoreNull(String value) {
        return value == null ? "" : value;
    }

    /**
     * 将对象转成字符串，null将转为空字符
     *
     * @param obj 原对象
     * @return 转换后的字符串
     */
    public static String valueOf(Object obj) {
        return (obj == null) ? "" : obj.toString();
    }

    /**
     * 求解两个字符号的最长公共子串
     *
     * @param strOne 字符串1
     * @param strTwo 字符串2
     * @return 最长公共子串
     */
    public static String maxSubstring1(String strOne, String strTwo) {
        // 参数检查
        if (strOne == null || strTwo == null) {
            return null;
        }
        if (strOne.isEmpty() || strTwo.isEmpty()) {
            return "";
        }
        // 二者中较长的字符串
        String max = "";
        // 二者中较短的字符串
        String min = "";
        if (strOne.length() < strTwo.length()) {
            max = strTwo;
            min = strOne;
        } else {
            max = strOne;
            min = strTwo;
        }
        String current = "";
        String result = "";
        // 遍历较短的字符串，并依次减少短字符串的字符数量，判断长字符是否包含该子串
        for (int i = 0; i < min.length(); i++) {
            for (int begin = 0, end = min.length() - i; end <= min.length(); begin++, end++) {
                current = min.substring(begin, end);
                if (max.contains(current) && current.length() > result.length()) {
                    result = current;
                }
            }
        }
        return result;
    }

    /**
     * 求解两个字符号的最长公共子串
     *
     * @param strOne 字符串1
     * @param strTwo 字符串2
     * @return 最长公共子串
     */
    public static String maxSubstring(String strOne, String strTwo) {
        // 参数检查
        if (strOne == null || strTwo == null) {
            return null;
        }
        if (strOne.isEmpty() || strTwo.isEmpty()) {
            return null;
        }
        // 矩阵的横向长度
        int len1 = strOne.length();
        // 矩阵的纵向长度
        int len2 = strTwo.length();

        // 保存矩阵的上一行
        int[] topLine = new int[len1];
        // 保存矩阵的当前行
        int[] currentLine = new int[len1];
        // 矩阵元素中的最大值
        int maxLen = 0;
        // 矩阵元素最大值出现在第几列
        int pos = 0;
        char ch = ' ';
        for (int i = 0; i < len2; i++) {
            ch = strTwo.charAt(i);
            // 遍历str1，填充当前行的数组
            for (int j = 0; j < len1; j++) {
                if (ch == strOne.charAt(j)) {
                    // 如果当前处理的是矩阵的第一列，单独处理，因为其坐上角的元素不存在
                    if (j == 0) {
                        currentLine[j] = 1;
                    } else {
                        currentLine[j] = topLine[j - 1] + 1;
                    }
                    if (currentLine[j] > maxLen) {
                        maxLen = currentLine[j];
                        pos = j;
                    }
                }
            }
            // 将矩阵的当前行元素赋值给topLine数组; 并清空currentLine数组
            for (int k = 0; k < len1; k++) {
                topLine[k] = currentLine[k];
                currentLine[k] = 0;
            }
            // 或者采用下面的方法
            // topLine = currentLine;
            // currentLine = new int[len1];
        }
        return strOne.substring(pos - maxLen + 1, pos + 1);
    }

    /**
     * hex转byte数组
     *
     * @param hex 16进制字符串
     * @return byte数组
     */
    public static byte[] hexToByte(String hex) {
        int m = 0, n = 0;
        // 每两个字符描述一个字节
        int byteLen = hex.length() / 2;
        byte[] ret = new byte[byteLen];
        for (int i = 0; i < byteLen; i++) {
            m = i * 2 + 1;
            n = m + 1;
            int intVal = Integer.decode("0x" + hex.substring(i * 2, m) + hex.substring(m, n));
            ret[i] = (byte) intVal;
        }
        return ret;
    }

    /**
     * byte数组转hex
     *
     * @param bytes byte数组
     * @return 16进制字符串
     */
    public static String byteToHex(byte[] bytes) {
        String strHex = "";
        StringBuilder sb = new StringBuilder();
        for (byte aByte : bytes) {
            strHex = Integer.toHexString(aByte & 0xFF);
            // 每个字节由两个字符表示，位数不够，高位补0
            sb.append((strHex.length() == 1) ? "0" + strHex : strHex);
        }
        return sb.toString().trim();
    }

    /**
     * 16进制直接转换成为字符串
     *
     * @param hexString 16进制字符串
     * @return String （字符集：UTF-8）
     * @explain 16进制字符串转换成为字符串
     */
    public static String fromHexString(String hexString) throws Exception {
        return fromHexString(hexString, StandardCharsets.UTF_8);
    }

    public static String fromHexString(String hexString, Charset charset) throws Exception {
        if (hexString == null || hexString.isEmpty()) {
            return "";
        }
        if (hexString.length() % 2 != 0) {
            throw new IllegalArgumentException("Invalid hex string: odd length");
        }
        // 转大写
        hexString = hexString.toUpperCase();
        // 16进制字符
        String hexDigital = "0123456789ABCDEF";
        // 将16进制字符串转换成char数组
        char[] hexs = hexString.toCharArray();
        // 能被16整除，肯定可以被2整除
        byte[] bytes = new byte[hexString.length() / 2];

        for (int i = 0; i < bytes.length; i++) {
            int high = hexDigital.indexOf(hexs[2 * i]);
            int low = hexDigital.indexOf(hexs[2 * i + 1]);
            if (high == -1 || low == -1) {
                throw new IllegalArgumentException("Invalid hex character in string");
            }
            int n = high * 16 + low;
            bytes[i] = (byte) (n & 0xff);
        }
        // byte[]--&gt;String
        return new String(bytes, charset);
    }

    /**
     * 字符串转换成为16进制字符串（大写）
     *
     * @param str 字符串（去除java转义字符）
     * @return 16进制字符串
     * @throws Exception 异常
     * 因为java转义字符串在java中有着特殊的意义，
     * 所以当字符串中包含转义字符串，并将其转换成16进制后，16进制再转成String时，会出问题：
     * java会将其当做转义字符串所代表的含义解析出来
     */
    public static String toHexString(String str) throws Exception {
        if (str == null) {
            return null;
        }
        // 1.校验是否包含特殊字符内容
        // java特殊转义符
        // String[] escapeArray = {"\b","\t","\n","\f","\r","\'","\"","\\"};
        String[] escapeArray = {"\b", "\t", "\n", "\f", "\r"};
        // 用于校验参数是否包含特殊转义符
        boolean flag = false;
        // 迭代
        for (String esacapeStr : escapeArray) {
            // 一真则真
            if (str.contains(esacapeStr)) {
                flag = true;
                break;// 终止循环
            }
        }
        // 包含特殊字符
        if (flag) {
            throw new Exception("参数字符串不能包含转义字符！");
        }

        // 16进制字符
        char[] hexArray = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'};
        StringBuilder sb = new StringBuilder();
        // String--&gt;byte[]
        byte[] bs = str.getBytes(StandardCharsets.UTF_8); // 指定编码避免平台差异
        for (byte b : bs) {
            int high = (b & 0xf0) >> 4;
            sb.append(hexArray[high]);
            int low = b & 0x0f;
            sb.append(hexArray[low]);
        }
        return sb.toString();
    }

    /**
     * 获取两个字符串的相似度
     *
     * @param str1 字符串1
     * @param str2 字符串2
     */
    public static float levenshtein(String str1, String str2) {
        //计算两个字符串的长度。
        int len1 = str1.length();
        int len2 = str2.length();
        //建立上面说的数组，比字符长度大一个空间
        int[][] dif = new int[len1 + 1][len2 + 1];
        //赋初值，步骤B。
        for (int a = 0; a <= len1; a++) {
            dif[a][0] = a;
        }
        for (int a = 0; a <= len2; a++) {
            dif[0][a] = a;
        }
        //计算两个字符是否一样，计算左上的值
        int temp;
        for (int i = 1; i <= len1; i++) {
            for (int j = 1; j <= len2; j++) {
                if (str1.charAt(i - 1) == str2.charAt(j - 1)) {
                    temp = 0;
                } else {
                    temp = 1;
                }
                //取三个值中最小的
                dif[i][j] = min(dif[i - 1][j - 1] + temp, dif[i][j - 1] + 1,
                        dif[i - 1][j] + 1);
            }
        }
        //取数组右下角的值，同样不同位置代表不同字符串的比较
        //计算相似度
        return 1 - (float) dif[len1][len2] / Math.max(str1.length(), str2.length());
    }

    /**
     * 得到最小值
     *
     * @param is 整型数组
     * @return 整数
     */
    private static int min(int... is) {
        int min = Integer.MAX_VALUE;
        for (int i : is) {
            if (min > i) {
                min = i;
            }
        }
        return min;
    }

    private static final Pattern BLANK_PATTERN = Pattern.compile("\\s|\\p{Zs}");

    /**
     * 清除空字符
     *
     * @param str 原字符串
     * @return 没空格的字符串
     */
    public static String removeBlank(String str) {
        if (str == null) {
            return null;
        }
        Matcher m = BLANK_PATTERN.matcher(str);
        return m.replaceAll("");
    }

    /**
     * 逗号分隔字符串转Set
     *
     * @param str 字符串
     * @return set列表
     */
    public static Set<String> commaDelimitedListToSet(@Nullable String str) {
        return StringUtils.commaDelimitedListToSet(str);
    }

    /**
     * Set转逗号分隔字符串
     *
     * @param set set列表
     * @return 字符串
     */
    public static String collectionToCommaDelimitedString(@Nullable Set<String> set) {
        return StringUtils.collectionToCommaDelimitedString(set);
    }

     /**
     * 获取第一个非空的字符串
     *
     * @param values 字符串列表
     * @return 字符串
     */
    public static String firstNotEmpty(String... values) {
        for (String value : values) {
            if (StringUtil.isNotEmpty(value)) {
                return value;
            }
        }
        return null;
    }
}
