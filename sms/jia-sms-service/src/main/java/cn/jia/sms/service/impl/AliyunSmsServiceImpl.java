package cn.jia.sms.service.impl;

import cn.jia.core.util.JsonUtil;
import cn.jia.sms.entity.SmsBatchRecord;
import cn.jia.sms.entity.SmsSendResult;
import cn.jia.sms.service.SmsServiceProvider;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.dysmsapi.model.v20170525.SendSmsRequest;
import com.aliyuncs.dysmsapi.model.v20170525.SendSmsResponse;
import com.aliyuncs.http.MethodType;
import com.aliyuncs.profile.DefaultProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;
import java.util.Map;

@Slf4j
public class AliyunSmsServiceImpl implements SmsServiceProvider {
    
    @Value("${sms.aliyun.accessKeyId:}")
    private String accessKeyId;
    
    @Value("${sms.aliyun.accessKeySecret:}")
    private String accessKeySecret;
    
    @Value("${sms.aliyun.endpoint:cn-shenzhen}")
    private String endpoint;
    
    @Value("${sms.aliyun.domain:dysmsapi.aliyuncs.com}")
    private String domain;
    
    private static final String PRODUCT = "Dysmsapi";
    
    /**
     * 初始化阿里云ACS客户端
     * @return ACS客户端实例
     */
    private IAcsClient initializeClient() {
        // 设置超时时间
        System.setProperty("sun.net.client.defaultConnectTimeout", "10000");
        System.setProperty("sun.net.client.defaultReadTimeout", "10000");
        
        // 初始化ascClient
        DefaultProfile profile = DefaultProfile.getProfile(endpoint, accessKeyId, accessKeySecret);
        DefaultProfile.addEndpoint(endpoint, PRODUCT, domain);
        return new DefaultAcsClient(profile);
    }
    
    /**
     * 将响应结果转换为SmsSendResult对象
     * @param code 响应代码
     * @param message 响应消息
     * @param bizId 业务ID
     * @return SmsSendResult对象
     */
    private SmsSendResult createSmsResult(String code, String message, String bizId) {
        SmsSendResult result = new SmsSendResult();
        result.setSuccess("OK".equals(code));
        result.setCode(code);
        result.setMessage(message);
        result.setMsgId(bizId);
        return result;
    }
    
    /**
     * 处理异常并生成错误结果
     * @param e 异常对象
     * @param operation 操作描述
     * @return 错误结果
     */
    private SmsSendResult handleException(Exception e, String operation) {
        log.error(operation, e);
        SmsSendResult result = new SmsSendResult();
        result.setSuccess(false);
        result.setMessage("发送异常: " + e.getMessage());
        return result;
    }
    
    @Override
    public SmsSendResult sendSms(String signature, String mobile, String content, String ext) {
        log.info("使用阿里云短信服务发送短信: mobile={}, content={}, xh={}", mobile, content, ext);
        throw new UnsupportedOperationException("阿里云不支持单条发送短信");
    }
    
    @Override
    public SmsSendResult sendSmsBatch(String signature, List<SmsBatchRecord> records, String time) {
        log.info("使用阿里云短信服务批量发送短信: signature={}, records={}, time={}", signature, records, time);
        throw new UnsupportedOperationException("阿里云不支持批量发送短信");
    }
    
    @Override
    public String balance() {
        // 阿里云没有直接的余额查询接口，需要通过控制台或者RAM账号查询
        return "请登录阿里云控制台查询余额";
    }
    
    /**
     * 发送模板短信
     *
     * @param mobile    手机号码
     * @param templateId      模板ID
     * @param signature 已报备的签名
     * @param tpContent 模板变量内容
     * @param ext       扩展号
     * @param extend    自定义参数
     * @param time      发送时间
     * @return 发送结果
     */
    @Override
    public SmsSendResult sendSmsTemplate(String mobile, String templateId, String signature,
            Map<String, String> tpContent, String ext, String extend, String time) {
        log.info("使用阿里云短信服务发送模板短信: mobile={}, tpId={}, signature={}", mobile, templateId, signature);
        
        try {
            IAcsClient acsClient = initializeClient();
            
            // 组装请求对象
            SendSmsRequest request = new SendSmsRequest();
            // 使用post提交
            request.setSysMethod(MethodType.POST);
            // 必填:待发送手机号
            request.setPhoneNumbers(mobile);
            // 必填:短信签名
            request.setSignName(signature);
            // 必填:短信模板Code
            request.setTemplateCode(templateId); // 阿里云使用字符串形式的模板CODE
            
            // 可选:模板中的变量替换JSON串
            if (tpContent != null && !tpContent.isEmpty()) {
                String templateParam = JsonUtil.toJson(tpContent);
                request.setTemplateParam(templateParam);
            }
            
            // 发起请求
            SendSmsResponse sendSmsResponse = acsClient.getAcsResponse(request);
            
            return createSmsResult(sendSmsResponse.getCode(), sendSmsResponse.getMessage(), sendSmsResponse.getBizId());
        } catch (Exception e) {
            return handleException(e, "阿里云模板短信发送异常");
        }
    }
}