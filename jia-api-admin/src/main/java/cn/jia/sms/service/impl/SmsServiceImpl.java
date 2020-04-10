package cn.jia.sms.service.impl;

import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.DataUtil;
import cn.jia.core.util.DateUtil;
import cn.jia.sms.common.Constants;
import cn.jia.sms.common.ErrorConstants;
import cn.jia.sms.dao.*;
import cn.jia.sms.entity.*;
import cn.jia.sms.service.SmsService;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Map;

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
	@Autowired
	private SmsPackageMapper smsPackageMapper;

	/**
	 * 插入短信表
	 * @param smsCode
	 */
	@Override
	public SmsCode create(SmsCode smsCode){
		smsCodeMapper.insertSelective(smsCode);
		return smsCode;
	}

	@Override
	public SmsCode update(SmsCode sc){
		smsCodeMapper.updateByPrimaryKeySelective(sc);
		return sc;
	}

	/**
	 * 查询是否有未使用邮箱验证码
	 * @param phone
	 * @param smsType
	 * @return
	 */
	@Override
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
	 * @param id
	 */
	@Override
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
	 * @throws Exception
	 */
	@Override
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
	public SmsCode find(Integer id) throws Exception {
		return smsCodeMapper.selectByPrimaryKey(id);
	}

	@Override
	public void delete(Integer id) {
		smsCodeMapper.deleteByPrimaryKey(id);
	}
	
	@Override
	@Transactional
	public void send(SmsSend smsSend) {
		smsSendMapper.insertSelective(smsSend);
		//设置最新剩余数量
		SmsConfig config = smsConfigMapper.selectByPrimaryKey(smsSend.getClientId());
		SmsConfig upConfig = new SmsConfig();
		upConfig.setClientId(smsSend.getClientId());
		upConfig.setRemain(config.getRemain() - 1);
		smsConfigMapper.updateByPrimaryKeySelective(upConfig);
	}
	
	@Override
	public SmsSend selectSend(String msgid) {
		return smsSendMapper.selectByPrimaryKey(msgid);
	}
	
	@Override
	public Page<SmsSend> listSend(SmsSendExample example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return smsSendMapper.selectByExamplePage(example);
	}

	@Override
	public List<Map<String, Object>> countSendByMobile(SmsSendExample example) {
		return smsSendMapper.groupByMobile(example);
	}

	@Override
	public void reply(SmsReply smsReply) {
		smsReplyMapper.insertSelective(smsReply);
	}
	
	@Override
	public Page<SmsReply> listReply(SmsReply example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return smsReplyMapper.selectByExamplePage(example);
	}

	@Override
	public SmsConfig selectConfig(String clientId) {
		return smsConfigMapper.selectByPrimaryKey(clientId);
	}

	@Override
	public void createConfig(SmsConfig config) {
		smsConfigMapper.insertSelective(config);
	}

	@Override
	public void updateConfig(SmsConfig config) {
		config.setRemain(null); //不能直接更新剩余数量
		smsConfigMapper.updateByPrimaryKeySelective(config);
	}

	@Override
	@Transactional
	public SmsBuy buy(int packageId, String clientId) throws Exception {
		SmsPackage smsPackage = smsPackageMapper.selectByPrimaryKey(packageId);
		if(smsPackage == null){
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		int number = smsPackage.getNumber();
		double money = smsPackage.getMoney();

		int total = 0, remain = 0;
		//获取上次充值后的总充值数量
		SmsBuy lastBuy = smsBuyMapper.selectLastest(clientId);
		if(lastBuy != null) {
			if(Constants.SMS_BUY_STATUS_DRAFT.equals(lastBuy.getStatus())){
				throw new EsRuntimeException(ErrorConstants.SMS_BUY_HAS_DRAFT);
			}
			total = lastBuy.getTotal();
		}
		//获取最新剩余数量
		SmsConfig config = smsConfigMapper.selectByPrimaryKey(clientId);
		remain = config.getRemain();
		//新增购买记录
		long now = DateUtil.genTime(new Date());
		SmsBuy buy = new SmsBuy();
		buy.setClientId(clientId);
		buy.setNumber(number);
		buy.setMoney(money);
		buy.setTotal(total + number);
		buy.setRemain(remain + number);
		buy.setTime(now);
		smsBuyMapper.insertSelective(buy);
		//设置最新剩余数量
		SmsConfig upConfig = new SmsConfig();
		upConfig.setClientId(clientId);
		upConfig.setRemain(remain + number);
		smsConfigMapper.updateByPrimaryKeySelective(upConfig);
		return buy;
	}

	@Override
	public Page<SmsBuy> listBuy(SmsBuyExample example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return smsBuyMapper.selectByExamplePage(example);
	}

	@Override
	public SmsBuy findBuy(Integer id){
		return smsBuyMapper.selectByPrimaryKey(id);
	}

	@Override
	public void cancelBuy(Integer id){
		SmsBuy smsBuy = new SmsBuy();
		smsBuy.setId(id);
		smsBuy.setStatus(Constants.SMS_BUY_STATUS_CANCEL);
		smsBuyMapper.updateByPrimaryKeySelective(smsBuy);
	}

	@Override
	public SmsTemplate createTemplate(SmsTemplate sms) {
		long now = DateUtil.genTime(new Date());
		sms.setTemplateId(DataUtil.getRandom(false, 32));
		sms.setCreateTime(now);
		sms.setUpdateTime(now);
		smsTemplateMapper.insertSelective(sms);
		return sms;
	}

	@Override
	public SmsTemplate findTemplate(String templateId) throws Exception {
		return smsTemplateMapper.selectByPrimaryKey(templateId);
	}

	@Override
	public SmsTemplate updateTemplate(SmsTemplate sms) {
		long now = DateUtil.genTime(new Date());
		sms.setUpdateTime(now);
		smsTemplateMapper.updateByPrimaryKeySelective(sms);
		return sms;
	}

	@Override
	public void deleteTemplate(String templateId) {
		SmsTemplate sms = new SmsTemplate();
		sms.setTemplateId(templateId);
		long now = DateUtil.genTime(new Date());
		sms.setUpdateTime(now);
		sms.setStatus(Constants.COMMON_DISABLE);
		smsTemplateMapper.updateByPrimaryKeySelective(sms);
	}

	@Override
	public Page<SmsTemplate> listTemplate(SmsTemplateExample example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return smsTemplateMapper.selectByExamplePage(example);
	}

	@Override
	public Page<SmsPackage> listPackage(SmsPackage example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return smsPackageMapper.selectByExamplePage(example);
	}
}
