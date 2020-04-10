package cn.jia.core.util;

import cn.jia.core.exception.EsRuntimeException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.bind.DatatypeConverter;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 腾讯云签名工具类
 * @author chc
 */
public class TxCloudUtil {
    private static final Charset UTF8;

    public static String sign(String secretKey, String sigStr, String sigMethod) throws Exception{
        String sig;

        try {
            Mac mac = Mac.getInstance(sigMethod);
            SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(UTF8), mac.getAlgorithm());
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(sigStr.getBytes(UTF8));
            sig = DatatypeConverter.printBase64Binary(hash);
            return sig;
        } catch (Exception var7) {
            throw new EsRuntimeException(var7.getClass().getName() + "-" + var7.getMessage());
        }
    }

    public static String makeSignPlainText(TreeMap<String, String> requestParams, String reqMethod, String host, String path) {
        String retStr = "";
        retStr = retStr + reqMethod;
        retStr = retStr + host;
        retStr = retStr + path;
        retStr = retStr + buildParamStr(requestParams);
        return retStr;
    }

    public static String buildParamStr(TreeMap<String, String> requestParams) {
        String retStr = "";

        String key;
        String value;
        for(Iterator var3 = requestParams.keySet().iterator(); var3.hasNext(); retStr = retStr + key.replace("_", ".") + '=' + value) {
            key = (String)var3.next();
            value = requestParams.get(key);
            if (retStr.length() == 0) {
                retStr = retStr + '?';
            } else {
                retStr = retStr + '&';
            }
        }

        return retStr;
    }

    public static String sha256Hex(String s) throws Exception {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException var3) {
            throw new EsRuntimeException("SHA-256 is not supported." + var3.getMessage());
        }

        byte[] d = md.digest(s.getBytes(UTF8));
        return DatatypeConverter.printHexBinary(d).toLowerCase();
    }

    public static String sha256Hex(byte[] b) throws Exception {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException var3) {
            throw new EsRuntimeException("SHA-256 is not supported." + var3.getMessage());
        }

        byte[] d = md.digest(b);
        return DatatypeConverter.printHexBinary(d).toLowerCase();
    }

    public static byte[] hmac256(byte[] key, String msg) throws Exception {
        Mac mac;
        try {
            mac = Mac.getInstance("HmacSHA256");
        } catch (NoSuchAlgorithmException var6) {
            throw new EsRuntimeException("HmacSHA256 is not supported." + var6.getMessage());
        }

        SecretKeySpec secretKeySpec = new SecretKeySpec(key, mac.getAlgorithm());

        try {
            mac.init(secretKeySpec);
        } catch (InvalidKeyException var5) {
            throw new EsRuntimeException(var5.getClass().getName() + "-" + var5.getMessage());
        }

        return mac.doFinal(msg.getBytes(UTF8));
    }

    static {
        UTF8 = StandardCharsets.UTF_8;
    }

    public static Map<String, Object> dnsSend(String domain, String subDomain, String value, String dnsKey, String dnsToken, String action) throws Exception{
        String apiHost = "cns.api.qcloud.com";
        String apiPath = "/v2/index.php";
        String httpMethod = "GET";

        TreeMap<String, String> param = new TreeMap<>();
        param.put("Action", action);
        param.put("domain", domain);
        if("RecordCreate".equals(action)){
            param.put("subDomain", subDomain);
            param.put("recordType", "A");
            param.put("recordLine", "默认");
            param.put("value", value);
        }else if("RecordDelete".equals(action)){
            param.put("recordId", subDomain);
        }else if("RecordList".equals(action)){
            param.put("recordType", "A");
        }

        param.put("Timestamp", DateUtil.genTime(new Date()).toString());
        param.put("Nonce", DataUtil.getRandom(true, 5));
        param.put("SecretId", dnsKey);
        param.put("SignatureMethod", "HmacSHA1");
        String sign = sign(dnsToken, makeSignPlainText(param, httpMethod, apiHost, apiPath), "HmacSHA1");
//		param.put("Signature", URLEncoder.encode(sign, "UTF-8"));
        param.put("Signature", sign);
        String resp = HttpUtil.sendGet("https://" + apiHost + apiPath + buildParamStr(param));
        Map<String, Object> respMap = JSONUtil.jsonToMap(resp);
         if(!Integer.valueOf(0).equals(Objects.requireNonNull(respMap).get("code"))){
            throw new EsRuntimeException(String.valueOf(respMap.get("code")), String.valueOf(respMap.get("message")));
        }
        return respMap;
    }

    public static void main(String[] args) throws Exception {
        String apiHost = "cns.api.qcloud.com";
        String apiPath = "/v2/index.php";
        String httpMethod = "GET";

        TreeMap<String, String> param = new TreeMap<>();
        param.put("Action", "RecordList");
        param.put("domain", "czyfchina.com");
        param.put("subDomain", "test");
        param.put("Timestamp", "1559719918");
        param.put("Nonce", "9999");
        param.put("SecretId", "AKIDf8LIzWw6VfZEdmMPt77sOfgR2NTKrLUP");
        param.put("SignatureMethod", "HmacSHA1");
        String msg = TxCloudUtil.makeSignPlainText(param, httpMethod, apiHost, apiPath);
        System.out.println(msg);
        String sign = TxCloudUtil.sign("pdEdvFQO8ez4VjhQt88xXszrUs65vWrx", msg, "HmacSHA1");
        param.put("Signature", URLEncoder.encode(sign, "UTF-8"));
        System.out.println(sign);
    }
}
