package cn.jia.sms.service;

import cn.jia.sms.entity.*;
import com.github.pagehelper.Page;

public interface SmsService {
	
	SmsCode create(SmsCode sms);

	SmsCode find(Integer id) throws Exception;

	SmsCode update(SmsCode sms);

	void delete(Integer id);

	/**
	 * 查询是否有未使用邮箱验证码
	 * @param phone
	 * @param smsType
	 * @return
	 */
	SmsCode selectSmsCodeNoUsed(String phone, Integer smsType);
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
	String upsert(String phone, Integer smsType);
	
	/**
	 * 发送短信
	 * @param smsSend
	 */
	void send(SmsSend smsSend);
	
	/**
	 * 查找发送记录
	 * @param msgid
	 * @return
	 */
	SmsSend selectSend(String msgid);
	
	/**
	 * 发送记录列表
	 * @param example
	 * @return
	 */
	Page<SmsSend> listSend(SmsSendExample example, int pageNo, int pageSize);
	
	/**
	 * 回复短信
	 * @param smsReply
	 */
	void reply(SmsReply smsReply);
	
	/**
	 * 回复短信列表
	 * @param example
	 * @return
	 */
	Page<SmsReply> listReply(SmsReply example, int pageNo, int pageSize);
	
	/**
	 * 查找用户配置
	 * @param clientId
	 * @return
	 */
	SmsConfig selectConfig(String clientId);

	/**
	 * 创建用户配置信息
	 * @param config
	 */
	void createConfig(SmsConfig config);

	/**
	 * 更新用户配置信息
	 * @param config
	 */
	void updateConfig(SmsConfig config);
	
	/**
	 * 购买短信
	 * @param number
	 * @param money
	 */
	void buy(int number, double money);
	
	/**
	 * 创建短信模板
	 * @param sms
	 * @return
	 */
	SmsTemplate createTemplate(SmsTemplate sms);

	/**
	 * 获取短信模板信息
	 * @param templateId
	 * @return
	 * @throws Exception
	 */
	SmsTemplate findTemplate(String templateId) throws Exception;

	/**
	 * 更新短信模板信息
	 * @param sms
	 * @return
	 */
	SmsTemplate updateTemplate(SmsTemplate sms);

	/**
	 * 删除短信模板
	 * @param templateId
	 */
	void deleteTemplate(String templateId);
	
	/**
	 * 短信模板列表
	 * @param example
	 * @return
	 */
	Page<SmsTemplate> listTemplate(SmsTemplateExample example, int pageNo, int pageSize);
	
}
