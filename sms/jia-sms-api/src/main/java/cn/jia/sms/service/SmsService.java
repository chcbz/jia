package cn.jia.sms.service;

import cn.jia.sms.entity.*;
import com.github.pagehelper.PageInfo;

public interface SmsService {
	
	SmsCodeEntity create(SmsCodeEntity sms);

	SmsCodeEntity find(Long id) throws Exception;

	SmsCodeEntity update(SmsCodeEntity sms);

	void delete(Long id);

	/**
	 * 查询是否有未使用邮箱验证码
	 * @param phone
	 * @param smsType
	 * @return
	 */
	SmsCodeEntity selectSmsCodeNoUsed(String phone, Integer smsType);
	/**
	 * 验证码已经被使用
	 * @param id
	 */
	void useSmsCode(Long id);
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
	void send(SmsSendEntity smsSend);
	
	/**
	 * 查找发送记录
	 * @param msgid
	 * @return
	 */
	SmsSendEntity selectSend(String msgid);
	
	/**
	 * 发送记录列表
	 * @param example
	 * @return
	 */
	PageInfo<SmsSendEntity> listSend(SmsSendVO example, int pageNo, int pageSize);
	
	/**
	 * 回复短信
	 * @param smsReply
	 */
	void reply(SmsReplyEntity smsReply);
	
	/**
	 * 回复短信列表
	 * @param example
	 * @return
	 */
	PageInfo<SmsReplyEntity> listReply(SmsReplyEntity example, int pageNo, int pageSize);
	
	/**
	 * 查找用户配置
	 * @param clientId
	 * @return
	 */
	SmsConfigEntity selectConfig(String clientId);

	/**
	 * 创建用户配置信息
	 * @param config
	 */
	void createConfig(SmsConfigEntity config);

	/**
	 * 更新用户配置信息
	 * @param config
	 */
	void updateConfig(SmsConfigEntity config);
	
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
	SmsTemplateEntity createTemplate(SmsTemplateEntity sms);

	/**
	 * 获取短信模板信息
	 * @param templateId
	 * @return
	 * @throws Exception
	 */
	SmsTemplateEntity findTemplate(String templateId) throws Exception;

	/**
	 * 更新短信模板信息
	 * @param sms
	 * @return
	 */
	SmsTemplateEntity updateTemplate(SmsTemplateEntity sms);

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
	PageInfo<SmsTemplateEntity> listTemplate(SmsTemplateVO example, int pageNo, int pageSize);
	
}
