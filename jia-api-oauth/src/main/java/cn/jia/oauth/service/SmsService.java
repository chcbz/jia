package cn.jia.oauth.service;

import cn.jia.sms.entity.SmsCode;
import cn.jia.sms.entity.SmsConfig;
import cn.jia.sms.entity.SmsSend;
import cn.jia.sms.entity.SmsTemplate;

public interface SmsService {

	/**
	 * 查询是否有未使用邮箱验证码
	 * @param phone
	 * @param smsType
	 * @return
	 */
	SmsCode selectSmsCodeNoUsed(String phone, Integer smsType, String clientId);
	/**
	 * 验证码已经被使用
	 * @param id
	 */
	void useSmsCode(Integer id);
	/**
	 * 保存邮箱验证码信息
	 * @param phone 电话号码
	 * @param smsType 验证码类型
	 * @return 最新验证码
	 * @throws Exception
	 */
	String upsert(String phone, Integer smsType, String clientId);
	
	/**
	 * 发送短信
	 * @param smsSend
	 */
	void send(SmsSend smsSend);
	

	/**
	 * 查找用户配置
	 * @param clientId
	 * @return
	 */
	SmsConfig selectConfig(String clientId);

	/**
	 * 获取短信模板信息
	 * @param templateId
	 * @return
	 * @throws Exception
	 */
	SmsTemplate findTemplate(String templateId) throws Exception;

}
