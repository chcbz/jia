package cn.jia.oauth.service.impl;

import cn.jia.core.util.DataUtil;
import cn.jia.core.util.DateUtil;
import cn.jia.oauth.common.Constants;
import cn.jia.oauth.service.SmsService;
import cn.jia.sms.dao.*;
import cn.jia.sms.entity.SmsCode;
import cn.jia.sms.entity.SmsConfig;
import cn.jia.sms.entity.SmsSend;
import cn.jia.sms.entity.SmsTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

@Service
public class SmsServiceImpl implements SmsService {
	
	@Autowired
	private SmsCodeMapper smsCodeMapper;
	@Autowired
	private SmsSendMapper smsSendMapper;
	@Autowired
	private SmsReplyMapper smsReplyMapper;
	@Autowired
	private SmsConfigMapper smsConfigMapper;
	@Autowired
	private SmsBuyMapper smsBuyMapper;
	@Autowired
	private SmsTemplateMapper smsTemplateMapper;

	/**
	 * 插入短信表
	 * @param smsCode 短信验证码
	 */
	private SmsCode create(SmsCode smsCode){
		smsCodeMapper.insertSelective(smsCode);
		return smsCode;
	}

	private SmsCode update(SmsCode sc){
		smsCodeMapper.updateByPrimaryKeySelective(sc);
		return sc;
	}

	/**
	 * 查询是否有未使用邮箱验证码
	 * @param phone 手机号码
	 * @param smsType 短信类型
	 * @return 验证码
	 */
	public SmsCode selectSmsCodeNoUsed(String phone, Integer smsType, String clientId){
		SmsCode example = new SmsCode();
		example.setPhone(phone);
		example.setClientId(clientId);
		example.setSmsType(smsType);
		example.setStatus(Constants.COMMON_ENABLE);
		List<SmsCode> smsCodeList = smsCodeMapper.selectByExample(example);
		SmsCode sc = null;
		if(smsCodeList.size() > 0){
			sc = smsCodeList.get(0);
		}
		return sc;
	}
	/**
	 * 验证码已经被使用
	 * @param id 验证码ID
	 */
	public void useSmsCode(Integer id){
		SmsCode sc = new SmsCode();
		sc.setId(id);
		sc.setStatus(Constants.COMMON_DISABLE);
		update(sc);
	}
	/**
	 * 保存验证码信息
	 * @param phone 电话号码
	 * @param smsType 验证码类型
	 * @return 最新验证码
	 */
	public String upsert(String phone, Integer smsType, String clientId){
		SmsCode smsCodeNoUsed = selectSmsCodeNoUsed(phone, smsType, clientId);
		long now = DateUtil.genTime(new Date());
		if(smsCodeNoUsed != null){
			smsCodeNoUsed.setCount(smsCodeNoUsed.getCount()+1);
			smsCodeNoUsed.setSmsCode(DataUtil.getRandom(true, 4));
			smsCodeNoUsed.setTime(now);
			update(smsCodeNoUsed);
			return smsCodeNoUsed.getSmsCode();
		}else{
			// 随机获取验证码
			String smsCode = DataUtil.getRandom(true, 4);
			SmsCode sc = new SmsCode();
			sc.setPhone(phone);
			sc.setSmsCode(smsCode);
			sc.setSmsType(smsType);
			sc.setClientId(clientId);
			sc.setTime(now);
			sc.setCount(1);
			sc.setStatus(Constants.COMMON_ENABLE);
			create(sc);
			return smsCode;
		}
	}

	@Override
	@Transactional
	public void send(SmsSend smsSend) {
		String clientId = smsSend.getClientId();
		smsSendMapper.insertSelective(smsSend);
		//设置最新剩余数量
		SmsConfig config = smsConfigMapper.selectByPrimaryKey(clientId);
		SmsConfig upConfig = new SmsConfig();
		upConfig.setClientId(clientId);
		upConfig.setRemain(config.getRemain() - 1);
		smsConfigMapper.updateByPrimaryKeySelective(upConfig);
	}

	@Override
	public SmsConfig selectConfig(String clientId) {
		return smsConfigMapper.selectByPrimaryKey(clientId);
	}

	@Override
	public SmsTemplate findTemplate(String templateId) {
		return smsTemplateMapper.selectByPrimaryKey(templateId);
	}

}
