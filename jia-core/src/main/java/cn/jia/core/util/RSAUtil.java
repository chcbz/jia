
package cn.jia.core.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

import cn.jia.core.common.EsConstants;

public class RSAUtil{
	
	public static final String  SIGN_ALGORITHMS = "SHA1WithRSA";
	
	/**
	* RSA签名
	* @param content 待签名数据
	* @param privateKey 商户私钥
	* @param input_charset 编码格式
	* @return 签名值
	*/
	public static String sign(String content, String privateKey, String input_charset)
	{
        try 
        {
        	PKCS8EncodedKeySpec priPKCS8 	= new PKCS8EncodedKeySpec( Base64Util.decode(privateKey) ); 
        	KeyFactory keyf 				= KeyFactory.getInstance("RSA");
        	PrivateKey priKey 				= keyf.generatePrivate(priPKCS8);

            java.security.Signature signature = java.security.Signature
                .getInstance(SIGN_ALGORITHMS);

            signature.initSign(priKey);
            signature.update( content.getBytes(input_charset) );

            byte[] signed = signature.sign();
            
            return Base64Util.encode(signed);
        }
        catch (Exception e) 
        {
        	e.printStackTrace();
        }
        
        return null;
    }
	
	/**
	* RSA验签名检查
	* @param content 待签名数据
	* @param sign 签名值
	* @param ali_public_key 支付宝公钥
	* @param input_charset 编码格式
	* @return 布尔值
	*/
//	public static boolean verify(String content, String sign, String ali_public_key, String input_charset)
//	{
//		try {
//            PublicKey pubKey = getPublicKeyFromX509("RSA",
//                new ByteArrayInputStream(ali_public_key.getBytes()));
//
//            java.security.Signature signature = java.security.Signature
//                .getInstance(Constants.SIGN_ALGORITHMS);
//
//            signature.initVerify(pubKey);
//
//            if (StringUtils.isEmpty(input_charset)) {
//                signature.update(content.getBytes());
//            } else {
//                signature.update(content.getBytes(input_charset));
//            }
//
//            return signature.verify(Base64Util.decodeBase64(sign.getBytes()));
//        } catch (Exception e) {
//        	System.out.println("支付宝验签错误,RSAcontent = " + content + ",sign=" + sign + ",charset = " + input_charset);
//        	e.printStackTrace();
//        	return false;
//        }
//		
//	}
	
	public static PublicKey getPublicKeyFromX509(String algorithm,
            InputStream ins) throws Exception {
		KeyFactory keyFactory = KeyFactory.getInstance(algorithm);
		
		StringWriter writer = new StringWriter();
		StreamUtil.io(new InputStreamReader(ins), writer);
		
		byte[] encodedKey = writer.toString().getBytes();
		
		encodedKey = Base64Util.decodeBase64(encodedKey);
		
		return keyFactory.generatePublic(new X509EncodedKeySpec(encodedKey));
	}
	
	/**
	* 解密
	* @param content 密文
	* @param private_key 商户私钥
	* @param input_charset 编码格式
	* @return 解密后的字符串
	*/
	public static String decrypt(String content, String private_key, String input_charset) throws Exception {
        PrivateKey prikey = getPrivateKey(private_key);

        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.DECRYPT_MODE, prikey);

        InputStream ins = new ByteArrayInputStream(Base64Util.decode(content));
        ByteArrayOutputStream writer = new ByteArrayOutputStream();
        //rsa解密的字节大小最多是128，将需要解密的内容，按128位拆开解密
        byte[] buf = new byte[128];
        int bufl;

        while ((bufl = ins.read(buf)) != -1) {
            byte[] block = null;

            if (buf.length == bufl) {
                block = buf;
            } else {
                block = new byte[bufl];
                for (int i = 0; i < bufl; i++) {
                    block[i] = buf[i];
                }
            }

            writer.write(cipher.doFinal(block));
        }

        return new String(writer.toByteArray(), input_charset);
    }

	
	/**
	* 得到私钥
	* @param key 密钥字符串（经过base64编码）
	* @throws Exception
	*/
	public static PrivateKey getPrivateKey(String key) throws Exception {

		byte[] keyBytes;
		
		keyBytes = Base64Util.decode(key);
		
		PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
		
		KeyFactory keyFactory = KeyFactory.getInstance("RSA");
		
		PrivateKey privateKey = keyFactory.generatePrivate(keySpec);
		
		return privateKey;
	}
	
	/*
	 * RSA方式验签
	 */
	public static boolean rsaCheckContent(String content, String sign, String publicKey,
            String charset) throws AlipayApiException {
		try {
			PublicKey pubKey = getPublicKeyFromX509("RSA", new ByteArrayInputStream(publicKey.getBytes()));

			java.security.Signature signature = java.security.Signature.getInstance(EsConstants.SIGN_ALGORITHMS);

			signature.initVerify(pubKey);

			if (StringUtils.isEmpty(charset)) {
				signature.update(content.getBytes());
			} else {
				signature.update(content.getBytes(charset));
			}
			return signature.verify(Base64Util.decodeBase64(sign.getBytes()));
		} catch (Exception e) {
			throw new AlipayApiException("RSAcontent = " + content + ",sign=" + sign + ",charset = " + charset, e);
		}
	}
	
	/*
	 * RSA2方式验签
	 */
	public static boolean rsa256CheckContent(String content, String sign, String publicKey,
            String charset) throws AlipayApiException {
		try {
			PublicKey pubKey = getPublicKeyFromX509("RSA", new ByteArrayInputStream(publicKey.getBytes()));

			java.security.Signature signature = java.security.Signature
					.getInstance(EsConstants.SIGN_SHA256RSA_ALGORITHMS);

			signature.initVerify(pubKey);

			if (StringUtils.isEmpty(charset)) {
				signature.update(content.getBytes());
			} else {
				signature.update(content.getBytes(charset));
			}

			return signature.verify(Base64Util.decodeBase64(sign.getBytes()));
		} catch (Exception e) {
			throw new AlipayApiException("RSAcontent = " + content + ",sign=" + sign + ",charset = " + charset, e);
		}
	}
	
}
