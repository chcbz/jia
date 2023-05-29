package cn.jia.core.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;


/**
 * 3DES加密解密
 * 
 * @author lzh
 * 
 */
public class ThreeDesUtil {
	/**
	 * 定义 加密算法,可用DES,DESede,Blowfish
	 */
	private static final String ALGORITHM = "DESede";
	private static final String KEY = "abcd1234";
	/**
	 * 3des解码
	 * @param value	待解密字符串
	 * @return
	 * @throws Exception
	 */
	public static String decrypt3Des(String value){
		try {
			byte[] b = decryptMode(getKeyBytes(KEY), Base64.getDecoder().decode(value));
			return new String(b);
		} catch (Exception e) {
			return null;
		}
	}
	/**
	 * 3des加密
	 * @param value	待加密字符串
	 * @return
	 * @throws Exception
	 */
	public static String encrypt3Des(String value){
		String str = null;
		try {
			str = byte2Base64(encryptMode(getKeyBytes(KEY), value.getBytes()));
		} catch (Exception e) {
			// TODO: handle exception
		}
		return str;
	}
	/**
	 * 计算24位长的密码byte值,首先对原始密钥做MD5算hash值，再用前8位数据对应补全后8位
	 * @param strKey
	 * @return
	 * @throws Exception
	 */
	private static byte[] getKeyBytes(String strKey) throws Exception {
		if (null == strKey || strKey.length() < 1) {
			throw new Exception("key is null or empty!");
		}
		java.security.MessageDigest alg = java.security.MessageDigest.getInstance("MD5");
		alg.update(strKey.getBytes());
		byte[] bkey = alg.digest();
		int start = bkey.length;
		byte[] bkey24 = new byte[24];
		for (int i = 0; i < start; i++) {
			bkey24[i] = bkey[i];
		}
		for (int i = start; i < 24; i++) {
			bkey24[i] = bkey[i - start];
		}
		return bkey24;

	}
	/**
	 * 
	 * @param keybyte	加密密钥，长度为24字节
	 * @param src		被加密的数据缓冲区（源）
	 * @return
	 */
	private static byte[] encryptMode(byte[] keybyte, byte[] src) {
		try {
			// 生成密钥
			// 加密
			SecretKey deskey = new SecretKeySpec(keybyte, ALGORITHM);
			Cipher c1 = Cipher.getInstance(ALGORITHM);
			c1.init(Cipher.ENCRYPT_MODE, deskey);
			return c1.doFinal(src);
		} catch (java.security.NoSuchAlgorithmException e1) {
			e1.printStackTrace();
		} catch (javax.crypto.NoSuchPaddingException e2) {
			e2.printStackTrace();
		} catch (java.lang.Exception e3) {
			e3.printStackTrace();
		}
		return null;
	}
	/**
	 * 
	 * @param keybyte	加密密钥，长度为24字节
	 * @param src		加密后的缓冲区
	 * @return	
	 */
	private static byte[] decryptMode(byte[] keybyte, byte[] src) {
		try { // 生成密钥
			SecretKey deskey = new SecretKeySpec(keybyte, ALGORITHM);
			// 解密
			Cipher c1 = Cipher.getInstance(ALGORITHM);
			c1.init(Cipher.DECRYPT_MODE, deskey);
			return c1.doFinal(src);
		} catch (java.security.NoSuchAlgorithmException e1) {
			e1.printStackTrace();
		} catch (javax.crypto.NoSuchPaddingException e2) {
			e2.printStackTrace();
		} catch (java.lang.Exception e3) {
			e3.printStackTrace();
		}
		return null;
	}

	/**
	 * 转换成base64编码
	 *
	 * @param b 字节数组
	 * @return base64字符串
	 */
	private static String byte2Base64(byte[] b) {
		return Base64.getEncoder().encodeToString(b);
	}
}
