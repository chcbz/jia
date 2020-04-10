package cn.jia.core.util;

import cn.jia.core.common.EsConstants;
import cn.jia.core.util.codec.Base64;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;

public class AliPayUtil {
	/**
	 * 
	 * 根据反馈回来的信息，生成签名结果
	 * 
	 * 
	 * 
	 * @param params
	 * 通知返回来的参数数组
	 * 
	 * @param publicKey 比对的签名结果
	 * @param charset 编码
	 * @return 生成的签名结果
	 * 
	 */

	public static boolean getSignVeryfy(Map<String, String> params, String publicKey, String charset) {
		String sign = params.get("sign");
		String signType = params.get("sign_type");
//		//获取验签的字符串
		String content = getSignCheckContentV1(params);
		
		try {
			if (EsConstants.SIGN_TYPE_RSA.equals(signType)) {

			    return RSAUtil.rsaCheckContent(content, sign, publicKey, charset);

			} else if (EsConstants.SIGN_TYPE_RSA2.equals(signType)) {

			    return RSAUtil.rsa256CheckContent(content, sign, publicKey, charset);

			} else {
				return false;
			}
		} catch (AlipayApiException e) {
			System.out.println("Sign Type is Not Support : signType=" + signType);
			e.printStackTrace();
			return false;
		}
	}
	
	/** 
     * 除去数组中的空值和签名参数
     * @param sArray 签名参数组
     * @return 去掉空值与签名参数后的新签名参数组
     */
    public static Map<String, String> paraFilter(Map<String, String> sArray) {
        Map<String, String> result = new HashMap<>();
        if (sArray == null || sArray.size() <= 0) {
            return result;
        }
        for (String key : sArray.keySet()) {
            String value = sArray.get(key);
            if (value == null || value.equals("") || key.equalsIgnoreCase("sign")
                || key.equalsIgnoreCase("sign_type")) {
                continue;
            }
            result.put(key, value);
        }
        return result;
    }

    /** 
     * 把数组所有元素排序，并按照“参数=参数值”的模式用“&”字符拼接成字符串
     * @param params 需要排序并参与字符拼接的参数组
     * @return 拼接后字符串
     */
    public static String createLinkString(Map<String, String> params) {
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        StringBuilder prestr = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            String value = params.get(key);
            if (i == keys.size() - 1) {//拼接时，不包括最后一个&字符
                prestr.append(key).append("=").append(value);
            } else {
                prestr.append(key).append("=").append(value).append("&");
            }
        }
        return prestr.toString();
    }
    /** 
     * 把数组所有元素排序，并按照“参数=参数值”的模式用“&”字符拼接成字符串
     * @param sortedParams 需要排序并参与字符拼接的参数组
     * @return 拼接后字符串
     */
    public static String getSignContent(Map<String, String> sortedParams) {
        StringBuilder content = new StringBuilder();
        List<String> keys = new ArrayList<>(sortedParams.keySet());
        Collections.sort(keys);
        int index = 0;
        for (String key : keys) {
            String value = sortedParams.get(key);
            if (StringUtils.areNotEmpty(key, value)) {
                content.append(index == 0 ? "" : "&").append(key).append("=").append(value);
                index++;
            }
        }
        return content.toString();
    }
    
    /**
     *  rsa内容签名
     * 
     * @param content
     * @param privateKey
     * @param charset
     * @return
     * @throws AlipayApiException
     */
    public static String rsaSign(String content, String privateKey, String charset,
                                 String signType) throws AlipayApiException {

        if (EsConstants.SIGN_TYPE_RSA.equals(signType)) {

            return rsaSign(content, privateKey, charset);
        } else if (EsConstants.SIGN_TYPE_RSA2.equals(signType)) {

            return rsa256Sign(content, privateKey, charset);
        } else {

            throw new AlipayApiException("Sign Type is Not Support : signType=" + signType);
        }

    }
    
    /**
     * sha1WithRsa 加签
     * 
     * @param content
     * @param privateKey
     * @param charset
     * @return
     * @throws AlipayApiException
     */
    public static String rsaSign(String content, String privateKey,
                                 String charset) throws AlipayApiException{
        try {
            PrivateKey priKey = getPrivateKeyFromPKCS8(EsConstants.SIGN_TYPE_RSA2,
                new ByteArrayInputStream(privateKey.getBytes()));

            java.security.Signature signature = java.security.Signature
                .getInstance(EsConstants.SIGN_ALGORITHMS);

            signature.initSign(priKey);

            if (StringUtils.isEmpty(charset)) {
                signature.update(content.getBytes());
            } else {
                signature.update(content.getBytes(charset));
            }

            byte[] signed = signature.sign();

            return Base64Util.encode(signed);
        } catch (InvalidKeySpecException ie) {
            throw new AlipayApiException("RSA私钥格式不正确，请检查是否正确配置了PKCS8格式的私钥", ie);
        } catch (Exception e) {
            throw new AlipayApiException("RSAcontent = " + content + "; charset = " + charset, e);
        }
    }
    
    /**
     * sha256WithRsa 加签
     * 
     * @param content
     * @param privateKey
     * @param charset
     * @return
     * @throws AlipayApiException
     */
    public static String rsa256Sign(String content, String privateKey,
                                    String charset) throws AlipayApiException {

        try {
            PrivateKey priKey = getPrivateKeyFromPKCS8(EsConstants.SIGN_TYPE_RSA,
                new ByteArrayInputStream(privateKey.getBytes()));

            java.security.Signature signature = java.security.Signature
                .getInstance(EsConstants.SIGN_SHA256RSA_ALGORITHMS);

            signature.initSign(priKey);

            if (StringUtils.isEmpty(charset)) {
                signature.update(content.getBytes());
            } else {
                signature.update(content.getBytes(charset));
            }

            byte[] signed = signature.sign();

            return new String(Base64.encodeBase64(signed));
        } catch (Exception e) {
            throw new AlipayApiException("RSAcontent = " + content + "; charset = " + charset, e);
        }

    }
    
    public static PrivateKey getPrivateKeyFromPKCS8(String algorithm,
            InputStream ins) throws Exception {
		if (ins == null || StringUtils.isEmpty(algorithm)) {
		return null;
		}
		
		KeyFactory keyFactory = KeyFactory.getInstance(algorithm);
		
		byte[] encodedKey = StreamUtil.readText(ins).getBytes();
		
		encodedKey = Base64.decodeBase64(encodedKey);
		
		return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(encodedKey));
	}
    
    public static String getSignCheckContentV1(Map<String, String> params) {
        if (params == null) {
            return null;
        }
        //去掉sign和sign_type
        params.remove("sign");
        params.remove("sign_type");

        StringBuilder content = new StringBuilder();
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);

        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            String value = params.get(key);
            content.append(i == 0 ? "" : "&").append(key).append("=").append(value);
        }

        return content.toString();
    }
}
