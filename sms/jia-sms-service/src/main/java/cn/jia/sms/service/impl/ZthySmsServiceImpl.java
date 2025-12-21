package cn.jia.sms.service.impl;

import cn.jia.core.util.*;
import cn.jia.sms.entity.SmsBatchRecord;
import cn.jia.sms.entity.SmsSendResult;
import cn.jia.sms.service.SmsServiceProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ZthySmsServiceImpl implements SmsServiceProvider {
    @Autowired(required = false)
    private RestTemplate restTemplate;

    @Value("${sms.provider.zthy.send-sms-url:https://api-shss.zthysms.com/v2/sendSms}")
    private String sendSmsUrl;

    @Value("${sms.provider.zthy.send-sms-batch-url:https://api-shss.zthysms.com/v2/sendSmsPa}")
    private String sendSmsBatchUrl;

    @Value("${sms.provider.zthy.balance-url:https://api-shss.zthysms.com/v2/balance}")
    private String balanceUrl;

    @Value("${sms.provider.zthy.send-sms-template-url:https://api-shss.zthysms.com/v2/sendSmsTp}")
    private String sendSmsTemplateUrl;

    @Value("${sms.provider.zthy.username:}")
    private String smsUsername;

    @Value("${sms.provider.zthy.password:}")
    private String smsPassword;

    @Override
    public SmsSendResult sendSms(String mobile, String signature, String content, String ext) {
        try {
            AuthInfo authInfo = buildAuthInfo();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            MultiValueMap<String, Object> map = new LinkedMultiValueMap<>();
            map.add("username", smsUsername);
            map.add("tkey", authInfo.tKey);
            map.add("password", authInfo.password);
            map.add("mobile", mobile);
            map.add("content", "【" + signature + "】" + content);
            map.add("ext", ext);

            HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(map, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(sendSmsUrl, request, String.class);
            String responseBody = response.getBody();
            SmsSendResult resultMap = JsonUtil.fromJson(responseBody, SmsSendResult.class);

            SmsSendResult result = new SmsSendResult();
            String code = Optional.ofNullable(resultMap).map(SmsSendResult::getCode).orElse(null);
            result.setSuccess("200".equals(code));
            return result;
        } catch (Exception e) {
            return buildErrorResult("发送异常: " + e.getMessage());
        }
    }

    @Override
    public SmsSendResult sendSmsBatch(String signature, List<SmsBatchRecord> records, String time) {
        try {
            // 构造认证信息
            AuthInfo authInfo = buildAuthInfo();

            // 构造JSON请求体
            String requestBody = buildBatchRequestBody(authInfo, signature, records, time);

            HttpEntity<String> request = createJsonRequest(requestBody);

            ResponseEntity<String> response = restTemplate.postForEntity(sendSmsBatchUrl, request, String.class);

            // 解析响应JSON
            Map<String, Object> resultMap = JsonUtil.jsonToMap(response.getBody());
            return buildResultFromMap(resultMap);
        } catch (Exception e) {
            return buildErrorResult("发送异常: " + e.getMessage());
        }
    }

    @Override
    public String balance() {
        try {
            // 构造认证信息
            AuthInfo authInfo = buildAuthInfo();

            // 构造JSON请求体
            String requestBody = String.format("{\"username\":\"%s\",\"password\":\"%s\",\"tKey\":\"%s\"}",
                    authInfo.username, authInfo.password, authInfo.tKey);

            HttpEntity<String> request = createJsonRequest(requestBody);

            ResponseEntity<String> response = restTemplate.postForEntity(balanceUrl, request, String.class);

            // 解析响应JSON
            Map<String, Object> stringObjectMap = JsonUtil.jsonToMap(response.getBody());
            return Optional.ofNullable(stringObjectMap).map(obj -> obj.get("sumSms")).map(Object::toString).orElse("0");
        } catch (Exception e) {
            return "{\"msg\":\"查询余额异常\",\"code\":500,\"sumSms\":0}";
        }
    }

    /**
     * 发送模板短信
     *
     * @param mobile     手机号码
     * @param templateId 模板ID
     * @param signature  已报备的签名
     * @param tpContent  模板变量内容
     * @param ext        扩展号
     * @param extend     自定义参数
     * @param time       发送时间
     * @return 发送结果
     */
    @Override
    public SmsSendResult sendSmsTemplate(String mobile, String templateId, String signature,
            Map<String, String> tpContent, String ext, String extend, String time) {
        try {
            // 构造认证信息
            AuthInfo authInfo = buildAuthInfo();

            // 构造JSON请求体
            String requestBody = buildTemplateRequestBody(authInfo, templateId, signature, mobile, tpContent, ext, extend, time);

            HttpEntity<String> request = createJsonRequest(requestBody);

            ResponseEntity<String> response = restTemplate.postForEntity(sendSmsTemplateUrl, request, String.class);

            // 解析响应JSON
            Map<String, Object> resultMap = JsonUtil.jsonToMap(response.getBody());
            return buildResultFromMap(resultMap);
        } catch (Exception e) {
            return buildErrorResult("发送异常: " + e.getMessage());
        }
    }

    /**
     * 构造批量发送请求体
     *
     * @param authInfo 认证信息
     * @param records  发送记录
     * @param time     发送时间
     * @return 请求体字符串
     */
    private String buildBatchRequestBody(AuthInfo authInfo, String signature, List<SmsBatchRecord> records, String time) {
        StringBuilder jsonBuilder = new StringBuilder()
                .append("{")
                .append("\"username\":\"").append(authInfo.username).append("\",")
                .append("\"password\":\"").append(authInfo.password).append("\",")
                .append("\"tKey\":\"").append(authInfo.tKey).append("\"");

        if (time != null && !time.isEmpty()) {
            jsonBuilder.append(",\"time\":\"").append(time).append("\"");
        }

        jsonBuilder.append(",\"records\":[");

        for (int i = 0; i < records.size(); i++) {
            if (i > 0) {
                jsonBuilder.append(",");
            }

            SmsBatchRecord record = records.get(i);
            jsonBuilder.append("{")
                    .append("\"mobile\":\"").append(record.getMobile()).append("\",")
                    .append("\"content\":\"").append("【").append(signature).append("】")
                    .append(record.getContent()).append("\"");

            if (record.getExt() != null) {
                jsonBuilder.append(",\"ext\":\"").append(record.getExt()).append("\"");
            }

            if (record.getExtend() != null) {
                jsonBuilder.append(",\"extend\":\"").append(record.getExtend()).append("\"");
            }

            jsonBuilder.append("}");
        }

        jsonBuilder.append("]").append("}");
        return jsonBuilder.toString();
    }

    /**
     * 构造模板短信请求体
     *
     * @param authInfo  认证信息
     * @param templateId      模板ID
     * @param signature 签名
     * @param mobile    手机号
     * @param tpContent 模板内容
     * @param ext       扩展号
     * @param extend    自定义参数
     * @param time      发送时间
     * @return 请求体字符串
     */
    private String buildTemplateRequestBody(AuthInfo authInfo, String templateId, String signature, String mobile,
            Map<String, String> tpContent, String ext, String extend, String time) {
        StringBuilder jsonBuilder = new StringBuilder()
                .append("{")
                .append("\"username\":\"").append(authInfo.username).append("\",")
                .append("\"password\":\"").append(authInfo.password).append("\",")
                .append("\"tKey\":\"").append(authInfo.tKey).append("\",")
                .append("\"tpId\":").append(templateId).append(",")
                .append("\"signature\":\"").append("【").append(signature).append("】").append("\"")
                .append(",\"ext\":\"").append(ext != null && !ext.isEmpty() ? ext : "").append("\"")
                .append(",\"extend\":\"").append(extend != null && !extend.isEmpty() ? extend : "").append("\"");

        if (time != null && !time.isEmpty()) {
            jsonBuilder.append(",\"time\":\"").append(time).append("\"");
        }

        jsonBuilder.append(",\"records\":[");

        String[] mobiles = mobile.split(",");
        for (int i = 0; i < mobiles.length; i++) {
            if (i > 0) {
                jsonBuilder.append(",");
            }

            jsonBuilder.append("{")
                    .append("\"mobile\":\"").append(mobiles[i]).append("\"");

            // 添加模板变量内容
            if (tpContent != null && !tpContent.isEmpty()) {
                jsonBuilder.append(",\"tpContent\":{");
                int count = 0;
                for (Map.Entry<String, String> entry : tpContent.entrySet()) {
                    if (count > 0) {
                        jsonBuilder.append(",");
                    }
                    jsonBuilder.append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
                    count++;
                }
                jsonBuilder.append("}");
            }

            jsonBuilder.append("}");
        }

        return jsonBuilder.append("]").append("}").toString();
    }

    /**
     * 构造认证信息
     *
     * @return 认证信息对象
     */
    private AuthInfo buildAuthInfo() {
        long tKey = System.currentTimeMillis() / 1000;
        String password = Md5Util.str2Base32Md5(Md5Util.str2Base32Md5(smsPassword) + tKey);
        return new AuthInfo(smsUsername, password, tKey);
    }

    /**
     * 创建JSON类型的HTTP请求
     *
     * @param requestBody 请求体内容
     * @return HttpEntity对象
     */
    private HttpEntity<String> createJsonRequest(String requestBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(requestBody, headers);
    }

    /**
     * 根据响应Map构建SmsSendResult对象
     *
     * @param resultMap 响应Map
     * @return SmsSendResult对象
     */
    private SmsSendResult buildResultFromMap(Map<String, Object> resultMap) {
        if (resultMap == null || resultMap.isEmpty()) {
            return buildErrorResult("响应结果为空");
        }
        SmsSendResult result = new SmsSendResult();
        Integer code = (Integer) resultMap.get("code");
        if (code != null && code == 200) {
            result.setSuccess(true);
            result.setCode(String.valueOf(code));
            result.setMessage((String) resultMap.get("msg"));
            result.setMsgId((String) resultMap.get("msgId"));
        } else {
            result.setSuccess(false);
            result.setCode(String.valueOf(code));
            result.setMessage((String) resultMap.get("msg"));
        }
        return result;
    }

    /**
     * 构建错误结果
     *
     * @param message 错误消息
     * @return 错误结果
     */
    private SmsSendResult buildErrorResult(String message) {
        SmsSendResult result = new SmsSendResult();
        result.setSuccess(false);
        result.setMessage(message);
        return result;
    }

    /**
     * 认证信息内部类
     */
    private record AuthInfo(String username, String password, long tKey) {}
}