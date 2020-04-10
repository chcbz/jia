package cn.jia.core.util;

import org.apache.commons.lang3.StringUtils;

import java.security.MessageDigest;

/**
 * MD5加密类
 * @author lzh
 *
 */
public class MD5Util {
	
	private final static String[] hexDigits = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "a", "b", "c", "d", "e", "f"};     
	/**
	 * 生成字符串的MD5编码的完整的32位
	 * @param  orignal 原字符串
	 * @return 源字符串的MD5编码32位
	 */
	public static String str2Base32MD5(String orignal){
		String resultString=null;
		try{
			resultString=new String(orignal);
			MessageDigest md = MessageDigest.getInstance("MD5");
			resultString=byteArrayToHexString(md.digest(resultString.getBytes("UTF-8"))).toLowerCase();
		}catch(Exception ex){			
		}
		return resultString;
	}
	/**
	 * 获取手机号加密字符串
	 * 加密算法：手机号第1位与第3位互换，第2位与第4位互换，再经过MD5加密得到加密字符串
	 * @param phone	手机号
	 * @return
	 */
	public static String appDecryptMobile(String phone){
		if(StringUtils.isEmpty(phone))
			return null;
		char [] ch = phone.toCharArray();
		char temp = ch[0];
		ch[0] = ch[2];
		ch[2] = temp;
		
		temp = ch[1];
		ch[1] = ch[3];
		ch[3] = temp;
		
		String newPhone = String.valueOf(ch);
		String encryptString = str2Base32MD5(newPhone).toUpperCase();
		return encryptString;
	}
	
	/**
	* 转换字节数组为16进制字符串
	* @param b 字节数组
	* @return 16进制字符串
	*/
	private static String byteArrayToHexString(byte[] b) 
	{
		StringBuffer resultSb = new StringBuffer();
		for (int i = 0; i < b.length; i++) {
		  resultSb.append(byteToHexString(b[i]));
		}
		return resultSb.toString();
	}
	
	private static String byteToHexString(byte b){
		int n=b;
		if(n<0){
			n+=256;
		}		
		return hexDigits[n/16]+hexDigits[n%16];
	}
	
	public static void main(String[] args){
		System.out.println(str2Base32MD5("123"));
	}
	
}
