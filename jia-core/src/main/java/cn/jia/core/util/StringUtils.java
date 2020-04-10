package cn.jia.core.util;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 字符串工具类。
 * 
 * @author carver.gu
 * @since 1.0, Sep 12, 2009
 */
public abstract class StringUtils {

	private StringUtils() {}

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
			if ((Character.isWhitespace(value.charAt(i)) == false)) {
				return false;
			}
		}
		return true;
	}
	
	public static boolean isNotBlank(String str) {
        return !isEmpty(str);
    }
	
	public static boolean isEmpty(final CharSequence cs) {
        return cs == null || cs.length() == 0;
    }

    public static boolean isNotEmpty(final CharSequence cs) {
        return !StringUtils.isEmpty(cs);
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
		if(length < 1)
			return false;
		
		int i = 0;
		if(length > 1 && chars[0] == '-')
			i = 1;
		
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
	public static String stripNonValidXMLCharacters(String input) {
		if (input == null || ("".equals(input)))
			return "";
		StringBuilder out = new StringBuilder();
		char current;
		for (int i = 0; i < input.length(); i++) {
			current = input.charAt(i);
			if ((current == 0x9) || (current == 0xA) || (current == 0xD)
					|| ((current >= 0x20) && (current <= 0xD7FF))
					|| ((current >= 0xE000) && (current <= 0xFFFD))
					|| ((current >= 0x10000) && (current <= 0x10FFFF)))
				out.append(current);
		}
		return out.toString();
	}
	
	public static boolean equals(String obj1, String obj2) {
		return (obj1 != null) ? (obj1.equals(obj2)) : (obj2 == null);
	}
	
	public static String asciiToString(String value) {
		StringBuffer sbu = new StringBuffer();
		String[] chars = value.split(",");
		for (int i = 0; i < chars.length; i++) {
			sbu.append((char) Integer.parseInt(chars[i]));
		}
		return sbu.toString();
	}
	
	public static String stringToAscii(String value) {
		StringBuffer sbu = new StringBuffer();
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
	 * @param value
	 * @return
	 */
	public static String ignoreNull(String value) {
		return value == null ? "" : value;
	}
	
	/**
	 * 将对象转成字符串，null将转为空字符
	 * @param obj
	 * @return
	 */
	public static String valueOf(Object obj) {
        return (obj == null) ? "" : obj.toString();
    }

	/**
	 * 求解两个字符号的最长公共子串
	 * @param strOne
	 * @param strTwo
	 * @return
	 */
	public static String maxSubstring1(String strOne, String strTwo){
		// 参数检查
		if(strOne==null || strTwo == null){
			return null;
		}
		if(strOne.equals("") || strTwo.equals("")){
			return null;
		}
		// 二者中较长的字符串
		String max = "";
		// 二者中较短的字符串
		String min = "";
		if(strOne.length() < strTwo.length()){
			max = strTwo;
			min = strOne;
		} else{
			max = strTwo;
			min = strOne;
		}
		String current = "";
		// 遍历较短的字符串，并依次减少短字符串的字符数量，判断长字符是否包含该子串
		for(int i=0; i<min.length(); i++){
			for(int begin=0, end=min.length()-i; end<=min.length(); begin++, end++){
				current = min.substring(begin, end);
				if(max.contains(current)){
					return current;
				}
			}
		}
		return null;
	}
	
	/**
	 * 求解两个字符号的最长公共子串
	 * @param strOne
	 * @param strTwo
	 * @return
	 */
	public static String maxSubstring(String strOne, String strTwo){
		// 参数检查
		if(strOne==null || strTwo == null){
			return null;
		}
		if(strOne.equals("") || strTwo.equals("")){
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
		for(int i=0; i<len2; i++){
			ch = strTwo.charAt(i);
			// 遍历str1，填充当前行的数组
			for(int j=0; j<len1; j++){
				if( ch == strOne.charAt(j)){
					// 如果当前处理的是矩阵的第一列，单独处理，因为其坐上角的元素不存在
					if(j==0){
						currentLine[j] = 1;
					} else{
						currentLine[j] = topLine[j-1] + 1;
					}
					if(currentLine[j] > maxLen){
						maxLen = currentLine[j];
						pos = j;
					}
				}
			}
			// 将矩阵的当前行元素赋值给topLine数组; 并清空currentLine数组
			for(int k=0; k<len1; k++){
				topLine[k] = currentLine[k];
				currentLine[k] = 0;
			}
			// 或者采用下面的方法
			// topLine = currentLine;
			// currentLine = new int[len1];
		}
		return strOne.substring(pos-maxLen+1, pos+1);
	}

	/**
	 * hex转byte数组
	 * @param hex
	 * @return
	 */
	public static byte[] hexToByte(String hex){
		int m = 0, n = 0;
		int byteLen = hex.length() / 2; // 每两个字符描述一个字节
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
	 * @param bytes
	 * @return
	 */
	public static String byteToHex(byte[] bytes){
		String strHex = "";
		StringBuilder sb = new StringBuilder("");
		for (int n = 0; n < bytes.length; n++) {
			strHex = Integer.toHexString(bytes[n] & 0xFF);
			sb.append((strHex.length() == 1) ? "0" + strHex : strHex); // 每个字节由两个字符表示，位数不够，高位补0
		}
		return sb.toString().trim();
	}

	/**
	 * 16进制直接转换成为字符串
	 * @explain
	 * @param hexString 16进制字符串
	 * @return String （字符集：UTF-8）
	 */
	public static String fromHexString(String hexString) throws Exception {
		return fromHexString(hexString, StandardCharsets.UTF_8);
	}
	public static String fromHexString(String hexString, Charset charset) throws Exception {
		// 用于接收转换结果
		String result = "";
		// 转大写
		hexString = hexString.toUpperCase();
		// 16进制字符
		String hexDigital = "0123456789ABCDEF";
		// 将16进制字符串转换成char数组
		char[] hexs = hexString.toCharArray();
		// 能被16整除，肯定可以被2整除
		byte[] bytes = new byte[hexString.length() / 2];
		int n;

		for (int i = 0; i < bytes.length; i++) {
			n = hexDigital.indexOf(hexs[2 * i]) * 16 + hexDigital.indexOf(hexs[2 * i + 1]);
			bytes[i] = (byte) (n & 0xff);
		}
		// byte[]--&gt;String
		result = new String(bytes, charset);
		return result;
	}

	/**
	 * 字符串转换成为16进制字符串（大写）
	 * @explain 因为java转义字符串在java中有着特殊的意义，
	 *     所以当字符串中包含转义字符串，并将其转换成16进制后，16进制再转成String时，会出问题：
	 *  java会将其当做转义字符串所代表的含义解析出来
	 * @param str 字符串（去除java转义字符）
	 * @return 16进制字符串
	 * @throws Exception
	 */
	public static String toHexString(String str) throws Exception {
		// 用于接收转换结果
		String hexString = "";
		// 1.校验是否包含特殊字符内容
		// java特殊转义符
		// String[] escapeArray = {"\b","\t","\n","\f","\r","\'","\"","\\"};
		String[] escapeArray = {"\b","\t","\n","\f","\r"};
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
		if (flag) throw new Exception("参数字符串不能包含转义字符！");

		// 16进制字符
		char[] hexArray = {'0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F'};
		StringBuilder sb = new StringBuilder();
		// String--&gt;byte[]
		byte[] bs = str.getBytes();
		int bit;
		for (byte b : bs) {
			bit = (b & 0x0f0) >> 4;
			sb.append(hexArray[bit]);
			bit = b & 0x0f;
			sb.append(hexArray[bit]);
		}
		hexString = sb.toString();
		return hexString;
	}

	/**
	 * 获取两个字符串的相似度
	 * @param str1 字符串1
	 * @param str2 字符串2
	 */
	public static float levenshtein(String str1,String str2) {
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

	//得到最小值
	private static int min(int... is) {
		int min = Integer.MAX_VALUE;
		for (int i : is) {
			if (min > i) {
				min = i;
			}
		}
		return min;
	}

	public static void main(String[] args) {
		//要比较的两个字符串
		String str1 = "真正努力过得人才知道，天赋有多重要。";
		String str2 = "大部分成功靠得既不是厚积薄发的努力，也不是戏剧化的机遇，而是早就定好的出身和天赋。";
		System.out.println(levenshtein(str1.toLowerCase(),str2.toLowerCase()));
	}
}
