package cn.jia.sms.service;

import cn.jia.sms.entity.SmsBatchRecord;
import cn.jia.sms.entity.SmsSendResult;

import java.util.List;
import java.util.Map;

/**
 * 短信服务提供商接口
 */
public interface SmsServiceProvider {

    /**
     * 发送单条短信
     *
     * @param mobile  手机号码
     * @param signature 签名
     * @param content 短信内容
     * @param ext     扩展号码
     * @return 发送结果
     */
    SmsSendResult sendSms(String mobile, String signature, String content, String ext);

    /**
     * 批量个性化发送短信
     *
     * @param signature  签名
     * @param records 发送记录列表
     * @param time    发送时间
     * @return 发送结果
     */
    SmsSendResult sendSmsBatch(String signature, List<SmsBatchRecord> records, String time);

    /**
     * 查询余额
     *
     * @return 余额结果
     */
    String balance();

    /**
     * 发送模板短信
     *
     * @param mobile    手机号码列表，多个以逗号分隔
     * @param templateId      模板ID
     * @param signature 已报备的签名
     * @param tpContent 模板变量内容
     * @param ext       扩展号
     * @param extend    自定义参数
     * @param time      发送时间
     * @return 发送结果
     */
    SmsSendResult sendSmsTemplate(String mobile, String templateId, String signature, Map<String, String> tpContent,
            String ext, String extend, String time);
}