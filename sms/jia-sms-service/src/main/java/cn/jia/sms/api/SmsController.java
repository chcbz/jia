package cn.jia.sms.api;

import cn.jia.base.service.DictService;
import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.*;
import cn.jia.sms.common.SmsConstants;
import cn.jia.sms.common.SmsErrorConstants;
import cn.jia.sms.entity.*;
import cn.jia.sms.service.SmsService;
import com.github.pagehelper.PageInfo;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/sms")
public class SmsController {
	
	@Inject
	private SmsService smsService;
	@Autowired(required = false)
	private DictService dictService;

	private final RestTemplate restTemplate = new RestTemplate();
	
	private static final String sendSmsURL = "http://hy.mix2.zthysms.com/sendSms.do";
	private static final String sendSmsBatchURL = "http://hy.mix2.zthysms.com/sendSmsBatch.do";
	private static final String sendSmsBatchIdentityURL = "http://hy.mix2.zthysms.com/sendSmsBatchIdentity.do";
	private static final String balanceURL = "http://hy.mix2.zthysms.com/balance.do";

	/**
	 * 验证码已经被使用
	 * @param phone
	 * @param smsType
	 * @param smsCode
	 * @return
	 * @throws Exception
	 */
	@RequestMapping(value = "/validate", method = RequestMethod.GET)
	public Object validateSmsCode(@RequestParam String phone, @RequestParam Integer smsType, @RequestParam String smsCode) throws Exception {
		SmsCodeEntity code = smsService.selectSmsCodeNoUsed(phone, smsType);
		if(code == null || !smsCode.equals(code.getSmsCode())) {
			throw new EsRuntimeException(SmsErrorConstants.DATA_NOT_FOUND);
		}
		smsService.useSmsCode(code.getId());
		return JsonResult.success();
	}

	/**
	 * 提取并使用验证码
	 * @param phone 手机号码
	 * @param smsType 消息类型
	 * @return
	 * @throws Exception
	 */
	@RequestMapping(value = "/use", method = RequestMethod.GET)
	public Object useSmsCode(@RequestParam String phone, @RequestParam Integer smsType) throws Exception {
		SmsCodeEntity code = smsService.selectSmsCodeNoUsed(phone, smsType);
		if(code == null) {
			throw new EsRuntimeException(SmsErrorConstants.DATA_NOT_FOUND);
		}
		smsService.useSmsCode(code.getId());
		return JsonResult.success(code.getSmsCode());
	}
	
	/**
	 * 生成验证码信息
	 * @param phone 电话号码
	 * @param smsType 验证码类型
	 * @return 最新验证码
	 * @throws Exception
	 */
	@RequestMapping(value = "/gen", method = RequestMethod.GET)
	public Object gen(@RequestParam String phone, @RequestParam Integer smsType, @RequestParam(value="templateId", required=false) String templateId) throws Exception{
		//检查是否还有额度
		SmsConfigEntity config = smsService.selectConfig(EsSecurityHandler.clientId());
		if(config == null || config.getRemain() <= 0) {
			throw new EsRuntimeException(SmsErrorConstants.SMS_NOT_ENOUGH);
		}
		
		String smsCode = smsService.upsert(phone, smsType);

		templateId = StringUtil.isEmpty(templateId) ? SmsConstants.SMS_CODE_TEMPLATE_ID : templateId;
		SmsTemplateEntity template = smsService.findTemplate(templateId);
		if(template == null) {
			throw new EsRuntimeException(SmsErrorConstants.SMS_TEMPLATE_NOT_EXIST);
		}
		String content = "【" + config.getShortName() + "】" + smsService.findTemplate(templateId).getContent();
		content = content.replace("{0}", smsCode);
		String tkey = DateUtil.getDate("yyyyMMddHHmmss");
		String smsUsername =
				dictService.selectByTypeAndValue(SmsConstants.DICT_TYPE_SMS_CONFIG, SmsConstants.SMS_CONFIG_USERNAME).getName();
		String smsPassword = dictService.selectByTypeAndValue(SmsConstants.DICT_TYPE_SMS_CONFIG, SmsConstants.SMS_CONFIG_PASSWORD).getName();
		String passwd = Md5Util.str2Base32Md5(Md5Util.str2Base32Md5(smsPassword) + tkey);

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

		MultiValueMap<String, String> map= new LinkedMultiValueMap<>();
		map.add("username", smsUsername);
		map.add("tkey", tkey);
		map.add("password", passwd);
		map.add("mobile", phone);
		map.add("content", content);

		HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(map, headers);

		ResponseEntity<String> response = restTemplate.postForEntity(sendSmsURL, request , String.class);
		//将发送记录保存到系统里
		if("1".equals(Objects.requireNonNull(response.getBody()).split(",")[0])){
			SmsSendEntity smsSend = new SmsSendEntity();
			smsSend.setContent(content);
			smsSend.setMobile(phone);
			smsSend.setMsgid(response.getBody().split(",")[1]);
			smsService.send(smsSend);
			return JsonResult.success(smsCode);
		}else {
			return JsonResult.failure("E999", response.getBody());
		}
	}

	/**
	 * 发送单条短信
	 * @param mobile 手机号码
	 * @param content 短信内容
	 * @param xh 扩展的小号
	 * @return
	 */
	@RequestMapping(value = "/send", method = RequestMethod.POST)
	@ResponseBody
	public Object sendSms(@RequestParam String mobile, @RequestParam String content, @RequestParam(required=false) String xh) throws Exception {
		//检查是否还有额度
		SmsConfigEntity config = smsService.selectConfig(EsSecurityHandler.clientId());
		if(config == null || config.getRemain() <= 0) {
			throw new EsRuntimeException(SmsErrorConstants.SMS_NOT_ENOUGH);
		}

		String tkey = DateUtil.getDate("yyyyMMddHHmmss");
		String smsUsername = dictService.selectByTypeAndValue(SmsConstants.DICT_TYPE_SMS_CONFIG, SmsConstants.SMS_CONFIG_USERNAME).getName();
		String smsPassword = dictService.selectByTypeAndValue(SmsConstants.DICT_TYPE_SMS_CONFIG, SmsConstants.SMS_CONFIG_PASSWORD).getName();
		String passwd = Md5Util.str2Base32Md5(Md5Util.str2Base32Md5(smsPassword) + tkey);

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

		MultiValueMap<String, String> map= new LinkedMultiValueMap<>();
		map.add("username", smsUsername);
		map.add("tkey", tkey);
		map.add("password", passwd);
		map.add("mobile", mobile);
		map.add("content", "【" + config.getShortName() + "】" + content);

		HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(map, headers);

		ResponseEntity<String> response = restTemplate.postForEntity(sendSmsURL, request , String.class);
		//将发送记录保存到系统里
		if("1".equals(response.getBody().split(",")[0])){
			SmsSendEntity smsSend = new SmsSendEntity();
			smsSend.setContent(content);
			smsSend.setMobile(mobile);
			smsSend.setMsgid(response.getBody().split(",")[1]);
			smsSend.setXh(xh);
			smsService.send(smsSend);
			return JsonResult.success(response.getBody());
		}else {
			return JsonResult.failure("E999", response.getBody());
		}
	}

	/**
	 * 短信发送列表
	 * @param page
	 * @param request
	 * @return
	 */
	@RequestMapping(value = "/send/list", method = RequestMethod.POST)
	public Object listSend(@RequestBody JsonRequestPage<SmsSendVO> page, HttpServletRequest request) {
		SmsSendVO example = Optional.ofNullable(page.getSearch()).orElse(new SmsSendVO());
		example.setClientId(EsSecurityHandler.clientId());
		PageInfo<SmsSendEntity> list = smsService.listSend(example, page.getPageNum(), page.getPageSize());
		JsonResultPage<SmsSendEntity> result = new JsonResultPage<>(list.getList());
		result.setPageNum(list.getPageNum());
		result.setTotal(list.getTotal());
		return result;
	}

	/**
	 * 批量发送短信
	 * @param mobile 手机号码，多个以逗号隔开
	 * @param content 短信内容
	 * @param xh 扩展的小号
	 * @return
	 */
	@RequestMapping(value = "/sendBatch", method = RequestMethod.POST)
	@ResponseBody
	public Object sendSmsBatch(@RequestParam String mobile, @RequestParam String content, @RequestParam(required=false) String xh) throws Exception {
		//检查是否还有额度
		SmsConfigEntity config = smsService.selectConfig(EsSecurityHandler.clientId());
		if(config == null || config.getRemain() < mobile.split(",").length) {
			throw new EsRuntimeException(SmsErrorConstants.SMS_NOT_ENOUGH);
		}
		String tkey = DateUtil.getDate("yyyyMMddHHmmss");
		String smsUsername = dictService.selectByTypeAndValue(SmsConstants.DICT_TYPE_SMS_CONFIG, SmsConstants.SMS_CONFIG_USERNAME).getName();
		String smsPassword = dictService.selectByTypeAndValue(SmsConstants.DICT_TYPE_SMS_CONFIG, SmsConstants.SMS_CONFIG_PASSWORD).getName();
		String passwd = Md5Util.str2Base32Md5(Md5Util.str2Base32Md5(smsPassword) + tkey);

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

		MultiValueMap<String, String> map= new LinkedMultiValueMap<>();
		map.add("username", smsUsername);
		map.add("tkey", tkey);
		map.add("password", passwd);
		map.add("mobile", mobile);
		map.add("content", "【" + config.getShortName() + "】" + content);

		HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(map, headers);

		ResponseEntity<String> response = restTemplate.postForEntity(sendSmsBatchURL, request , String.class);

		if("1".equals(Objects.requireNonNull(response.getBody()).split(",")[0])){
		    String[] mobiles = mobile.split(",");
			for(int i=0;i<mobiles.length;i++) {
                String m = mobiles[i];
				SmsSendEntity smsSend = new SmsSendEntity();
				smsSend.setContent(content);
				smsSend.setMobile(m.trim());
				smsSend.setMsgid(response.getBody().split(",")[1] + "-" + (i + 1));
				smsSend.setXh(xh);
				smsService.send(smsSend);
			}
			return JsonResult.success(response.getBody());
		}else {
			return JsonResult.failure("E999", response.getBody());
		}
	}

	/**
	 * 批量个性化短信
	 * @param mobile 手机号码，多个以逗号隔开
	 * @param content 短信内容，多个以逗号隔开，与手机号码匹配
	 * @param xh 扩展的小号
	 * @return
	 */
	@RequestMapping(value = "/sendSmsBatchIdentity", method = RequestMethod.POST)
	@ResponseBody
	public Object sendSmsBatchIdentity(@RequestParam String mobile, @RequestParam String content, @RequestParam(required=false) String xh) throws Exception {
		//检查是否还有额度
		SmsConfigEntity config = smsService.selectConfig(EsSecurityHandler.clientId());
		if(config == null || config.getRemain() < mobile.split(",").length) {
			throw new EsRuntimeException(SmsErrorConstants.SMS_NOT_ENOUGH);
		}
		String tkey = DateUtil.getDate("yyyyMMddHHmmss");
		String smsUsername = dictService.selectByTypeAndValue(SmsConstants.DICT_TYPE_SMS_CONFIG, SmsConstants.SMS_CONFIG_USERNAME).getName();
		String smsPassword = dictService.selectByTypeAndValue(SmsConstants.DICT_TYPE_SMS_CONFIG, SmsConstants.SMS_CONFIG_PASSWORD).getName();
		String passwd = Md5Util.str2Base32Md5(Md5Util.str2Base32Md5(smsPassword) + tkey);

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

		MultiValueMap<String, String> map= new LinkedMultiValueMap<>();
		map.add("username", smsUsername);
		map.add("tkey", tkey);
		map.add("password", passwd);
		map.add("mobile", mobile);
		map.add("content", Arrays.stream(content.split("※")).map(c -> "【" + config.getShortName() + "】" + c).collect(Collectors.joining("※")));

		HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(map, headers);

		ResponseEntity<String> response = restTemplate.postForEntity(sendSmsBatchIdentityURL, request , String.class);

		if("1".equals(Objects.requireNonNull(response.getBody()).split(",")[0])){
			String[] mobiles = mobile.split(",");
			for(int i=0;i<mobiles.length;i++) {
				SmsSendEntity smsSend = new SmsSendEntity();
				smsSend.setContent(content.split("※")[i]);
				smsSend.setMobile(mobiles[i]);
				smsSend.setMsgid(response.getBody().split(",")[1] + "-" + (i + 1));
				smsSend.setXh(xh);
				smsService.send(smsSend);
			}
			return JsonResult.success(response.getBody());
		}else {
			return JsonResult.failure("E999", response.getBody());
		}
	}

	/**
	 * 短信剩余条数查询
	 * @return
	 */
	@RequestMapping(value = "/balance", method = RequestMethod.GET)
	@ResponseBody
	public Object balance() {
		String tkey = DateUtil.getDate("yyyyMMddHHmmss");
		String smsUsername = dictService.selectByTypeAndValue(SmsConstants.DICT_TYPE_SMS_CONFIG, SmsConstants.SMS_CONFIG_USERNAME).getName();
		String smsPassword = dictService.selectByTypeAndValue(SmsConstants.DICT_TYPE_SMS_CONFIG, SmsConstants.SMS_CONFIG_PASSWORD).getName();
		String passwd = Md5Util.str2Base32Md5(Md5Util.str2Base32Md5(smsPassword) + tkey);
		
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

		MultiValueMap<String, String> map= new LinkedMultiValueMap<>();
		map.add("username", smsUsername);
		map.add("tkey", tkey);
		map.add("password", passwd);

		HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(map, headers);

		ResponseEntity<String> response = restTemplate.postForEntity(balanceURL, request , String.class);
		
		return JsonResult.success(response.getBody());
	}
	
	/**
	 * 接收短信回复
	 * @return
	 */
	@RequestMapping(value = "/receive", method = RequestMethod.GET)
	@ResponseBody
	public Object receive(@RequestParam String mobile, @RequestParam String content, @RequestParam String msgid, @RequestParam(required=false) String xh) {
		SmsReplyEntity smsReply = new SmsReplyEntity();
		smsReply.setContent(content);
		smsReply.setMobile(mobile);
		smsReply.setMsgid(msgid);
		smsReply.setXh(xh);
		smsService.reply(smsReply);
		
		SmsSendEntity send = smsService.selectSend(msgid);
		SmsConfigEntity config = smsService.selectConfig(send.getClientId());
		if(config != null && StringUtil.isNotEmpty(config.getReplyUrl())) {
			String replyUrl = config.getReplyUrl();
			replyUrl = HttpUtil.addUrlValue(replyUrl, "mobile", mobile);
			replyUrl = HttpUtil.addUrlValue(replyUrl, "content", content);
			replyUrl = HttpUtil.addUrlValue(replyUrl, "msgid", msgid);
			replyUrl = HttpUtil.addUrlValue(replyUrl, "xh", xh);
			
			String response = restTemplate.getForObject(replyUrl, String.class);
			log.info("sms reply success: " + response);
		} else {
			log.warn("sms reply no replyUrl");
		}
		
		return JsonResult.success();
	}
	
	/**
	 * 短信回复列表
	 * @param page
	 * @param request
	 * @return
	 */
	@RequestMapping(value = "/reply/list", method = RequestMethod.POST)
	public Object listReply(@RequestBody JsonRequestPage<SmsReplyEntity> page, HttpServletRequest request) {
		SmsReplyEntity example = Optional.ofNullable(page.getSearch()).orElse(new SmsReplyEntity());
		PageInfo<SmsReplyEntity> list = smsService.listReply(example, page.getPageNum(), page.getPageSize());
		JsonResultPage<SmsReplyEntity> result = new JsonResultPage<>(list.getList());
		result.setPageNum(list.getPageNum());
		result.setTotal(list.getTotal());
		return result;
	}
	
	/**
	 * 获取短信配置信息
	 * @return
	 */
	@RequestMapping(value = "/config/get", method = RequestMethod.GET)
	public Object findConfig() {
		SmsConfigEntity config = smsService.selectConfig(EsSecurityHandler.clientId());
		return JsonResult.success(config);
	}
	
	/**
	 * 更新短信配置信息
	 * @param config
	 * @return
	 */
	@RequestMapping(value = "/config/update", method = RequestMethod.POST)
	public Object updateConfig(@RequestBody SmsConfigEntity config) {
		config.setClientId(EsSecurityHandler.clientId());
		smsService.updateConfig(config);
		return JsonResult.success(config);
	}
	
	/**
	 * 获取短信模板信息
	 * @param templateId
	 * @return
	 */
	@RequestMapping(value = "/template/get", method = RequestMethod.GET)
	public Object findTemplateById(@RequestParam(name = "templateId") String templateId) throws Exception {
		SmsTemplateEntity sms = smsService.findTemplate(templateId);
		if(sms == null) {
			throw new EsRuntimeException(SmsErrorConstants.DATA_NOT_FOUND);
		}
		return JsonResult.success(sms);
	}

	/**
	 * 创建短信模板
	 * @param sms
	 * @return
	 */
	@RequestMapping(value = "/template/create", method = RequestMethod.POST)
	public Object createTemplate(@RequestBody SmsTemplateEntity sms) {
		smsService.createTemplate(sms);
		return JsonResult.success(sms);
	}

	/**
	 * 更新短信模板信息
	 * @param sms
	 * @return
	 */
	@RequestMapping(value = "/template/update", method = RequestMethod.POST)
	public Object updateTemplate(@RequestBody SmsTemplateEntity sms) {
		smsService.updateTemplate(sms);
		return JsonResult.success(sms);
	}

	/**
	 * 删除短信模板
	 * @param templateId
	 * @return
	 */
	@RequestMapping(value = "/template/delete", method = RequestMethod.GET)
	public Object deleteTemplate(@RequestParam(name = "templateId") String templateId) {
		smsService.deleteTemplate(templateId);
		return JsonResult.success();
	}
	
	/**
	 * 短信模板列表
	 * @param page
	 * @param request
	 * @return
	 */
	@RequestMapping(value = "/template/list", method = RequestMethod.POST)
	public Object listTemplate(@RequestBody JsonRequestPage<SmsTemplateVO> page, HttpServletRequest request) {
		SmsTemplateVO example = Optional.ofNullable(page.getSearch()).orElse(new SmsTemplateVO());
		example.setClientId(EsSecurityHandler.clientId());
		PageInfo<SmsTemplateEntity> list = smsService.listTemplate(example, page.getPageNum(), page.getPageSize());
		JsonResultPage<SmsTemplateEntity> result = new JsonResultPage<>(list.getList());
		result.setPageNum(list.getPageNum());
		result.setTotal(list.getTotal());
		return result;
	}
	
	/**
	 * 购买短信套餐
	 * @param number
	 * @param money
	 * @return
	 */
	@RequestMapping(value = "/buy", method = RequestMethod.GET)
	public Object buy(@RequestParam Integer number, @RequestParam Double money) {
		smsService.buy(number, money);
		return JsonResult.success();
	}
}
