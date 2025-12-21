package cn.jia.sms.service;

import cn.jia.sms.entity.*;
import com.github.pagehelper.PageInfo;

/**
 * 短信服务接口
 * 定义了短信验证码、发送记录、回复记录、用户配置和短信模板等相关操作
 */
public interface SmsService {
	
	/**
	 * 创建短信验证码记录
	 * @param sms 短信验证码实体
	 * @return 创建后的短信验证码实体
	 */
	SmsCodeEntity create(SmsCodeEntity sms);

	/**
	 * 根据ID查找短信验证码
	 * @param id 短信验证码ID
	 * @return 短信验证码实体
	 * @throws Exception 查找异常
	 */
	SmsCodeEntity find(Long id) throws Exception;

	/**
	 * 更新短信验证码记录
	 * @param sms 短信验证码实体
	 * @return 更新后的短信验证码实体
	 */
	SmsCodeEntity update(SmsCodeEntity sms);

	/**
	 * 删除短信验证码记录
	 * @param id 短信验证码ID
	 */
	void delete(Long id);

	/**
	 * 查询是否有未使用的短信验证码
	 * @param phone 手机号码
	 * @param smsType 短信类型
	 * @return 短信验证码实体，如果没有未使用的则返回null
	 */
	SmsCodeEntity selectSmsCodeNoUsed(String phone, Integer smsType);
	
	/**
	 * 标记验证码已被使用
	 * @param id 验证码ID
	 */
	void useSmsCode(Long id);
	
	/**
	 * 保存短信验证码信息，如果存在未使用的相同类型验证码则直接返回，否则创建新的验证码
	 * @param phone 电话号码
	 * @param smsType 验证码类型
	 * @return 最新验证码字符串
	 * @throws Exception 保存过程中出现的异常
	 */
	String upsert(String phone, Integer smsType) throws Exception;
	
	/**
	 * 发送短信
	 * @param smsSend 短信发送实体
	 */
	void send(SmsSendEntity smsSend);
	
	/**
	 * 根据消息ID查找发送记录
	 * @param msgid 消息ID
	 * @return 短信发送实体
	 */
	SmsSendEntity selectSend(String msgid);
	
	/**
	 * 获取短信发送记录列表
	 * @param example 查询条件
	 * @param pageNo 页码
	 * @param pageSize 每页数量
	 * @return 分页结果
	 */
	PageInfo<SmsSendEntity> listSend(SmsSendVO example, int pageNo, int pageSize);
	
	/**
	 * 处理短信回复
	 * @param smsReply 短信回复实体
	 */
	void reply(SmsReplyEntity smsReply);
	
	/**
	 * 获取短信回复记录列表
	 * @param example 查询条件
	 * @param pageNo 页码
	 * @param pageSize 每页数量
	 * @return 分页结果
	 */
	PageInfo<SmsReplyEntity> listReply(SmsReplyEntity example, int pageNo, int pageSize);
	
	/**
	 * 根据客户端ID查找短信配置
	 * @param clientId 客户端ID
	 * @return 短信配置实体
	 */
	SmsConfigEntity selectConfig(String clientId);

	/**
	 * 创建用户短信配置信息
	 * @param config 短信配置实体
	 */
	void createConfig(SmsConfigEntity config);

	/**
	 * 更新用户短信配置信息
	 * @param config 短信配置实体
	 */
	void updateConfig(SmsConfigEntity config);
	
	/**
	 * 购买短信包
	 * @param number 短信数量
	 * @param money 金额
	 */
	void buy(int number, double money);
	
	/**
	 * 创建短信模板
	 * @param sms 短信模板实体
	 * @return 创建后的短信模板实体
	 */
	SmsTemplateEntity createTemplate(SmsTemplateEntity sms);

	/**
	 * 根据模板ID获取短信模板信息
	 * @param templateId 模板ID
	 * @return 短信模板实体
	 * @throws Exception 查找异常
	 */
	SmsTemplateEntity findTemplate(String templateId) throws Exception;

	/**
	 * 更新短信模板信息
	 * @param sms 短信模板实体
	 * @return 更新后的短信模板实体
	 */
	SmsTemplateEntity updateTemplate(SmsTemplateEntity sms);

	/**
	 * 删除短信模板
	 * @param templateId 模板ID
	 */
	void deleteTemplate(String templateId);
	
	/**
	 * 获取短信模板列表
	 * @param example 查询条件
	 * @param pageNo 页码
	 * @param pageSize 每页数量
	 * @return 分页结果
	 */
	PageInfo<SmsTemplateEntity> listTemplate(SmsTemplateVO example, int pageNo, int pageSize);
	
}