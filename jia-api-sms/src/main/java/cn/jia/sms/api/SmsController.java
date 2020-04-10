package cn.jia.sms.api;

import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.entity.JSONRequestPage;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.entity.JSONResultPage;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.service.DictService;
import cn.jia.core.util.*;
import cn.jia.sms.common.Constants;
import cn.jia.sms.common.ErrorConstants;
import cn.jia.sms.entity.*;
import cn.jia.sms.service.SmsService;
import com.github.pagehelper.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Date;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/sms")
public class SmsController {
	
	@Autowired
	private SmsService smsService;
	@Autowired
	private DictService dictService;
	@Value("${security.oauth2.resource.id}")
	private String resourceId;
	
	private RestTemplate restTemplate = new RestTemplate();
	
	private static String sendSmsURL = "http://hy.mix2.zthysms.com/sendSms.do";
	private static String sendSmsBatchURL = "http://hy.mix2.zthysms.com/sendSmsBatch.do";
	private static String sendSmsBatchIdentityURL = "http://hy.mix2.zthysms.com/sendSmsBatchIdentity.do";
	private static String balanceURL = "http://hy.mix2.zthysms.com/balance.do";

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
		SmsCode code = smsService.selectSmsCodeNoUsed(phone, smsType);
		if(code == null || !smsCode.equals(code.getSmsCode())) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		smsService.useSmsCode(code.getId());
		return JSONResult.success();
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
		SmsCode code = smsService.selectSmsCodeNoUsed(phone, smsType);
		if(code == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		smsService.useSmsCode(code.getId());
		return JSONResult.success(code.getSmsCode());
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
		SmsConfig config = smsService.selectConfig(EsSecurityHandler.clientId());
		if(config == null || config.getRemain() <= 0) {
			throw new EsRuntimeException(ErrorConstants.SMS_NOT_ENOUGH);
		}
		
		String smsCode = smsService.upsert(phone, smsType);

		templateId = StringUtils.isEmpty(templateId) ? Constants.SMS_CODE_TEMPLATE_ID : templateId;
		SmsTemplate template = smsService.findTemplate(templateId);
		if(template == null) {
			throw new EsRuntimeException(ErrorConstants.SMS_TEMPLATE_NOT_EXIST);
		}
		String content = "【" + config.getShortName() + "】" + smsService.findTemplate(templateId).getContent();
		content = content.replace("{0}", smsCode);
		String tkey = DateUtil.getDate("yyyyMMddHHmmss");
		String smsUsername = dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_SMS_CONFIG, Constants.SMS_CONFIG_USERNAME).getName();
		String smsPassword = dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_SMS_CONFIG, Constants.SMS_CONFIG_PASSWORD).getName();
		String passwd = MD5Util.str2Base32MD5(MD5Util.str2Base32MD5(smsPassword) + tkey);
		
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
		if("1".equals(response.getBody().split(",")[0])){
			SmsSend smsSend = new SmsSend();
			smsSend.setContent(content);
			smsSend.setMobile(phone);
			smsSend.setMsgid(response.getBody().split(",")[1]);
			smsSend.setTime(DateUtil.genTime(new Date()));
			smsService.send(smsSend);
			return JSONResult.success(smsCode);
		}else {
			return JSONResult.failure("E999", response.getBody());
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
		SmsConfig config = smsService.selectConfig(EsSecurityHandler.clientId());
		if(config == null || config.getRemain() <= 0) {
			throw new EsRuntimeException(ErrorConstants.SMS_NOT_ENOUGH);
		}
		
		String tkey = DateUtil.getDate("yyyyMMddHHmmss");
		String smsUsername = dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_SMS_CONFIG, Constants.SMS_CONFIG_USERNAME).getName();
		String smsPassword = dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_SMS_CONFIG, Constants.SMS_CONFIG_PASSWORD).getName();
		String passwd = MD5Util.str2Base32MD5(MD5Util.str2Base32MD5(smsPassword) + tkey);
		
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
			SmsSend smsSend = new SmsSend();
			smsSend.setContent(content);
			smsSend.setMobile(mobile);
			smsSend.setMsgid(response.getBody().split(",")[1]);
			smsSend.setTime(DateUtil.genTime(new Date()));
			smsSend.setXh(xh);
			smsService.send(smsSend);
			return JSONResult.success(response.getBody());
		}else {
			return JSONResult.failure("E999", response.getBody());
		}
	}
	
	/**
	 * 短信发送列表
	 * @param page
	 * @param request
	 * @return
	 */
	@RequestMapping(value = "/send/list", method = RequestMethod.POST)
	public Object listSend(@RequestBody JSONRequestPage<String> page, HttpServletRequest request) {
		SmsSendExample example = JSONUtil.fromJson(page.getSearch(), SmsSendExample.class);
		if(example == null) {
			example = new SmsSendExample();
		}
		example.setClientId(EsSecurityHandler.clientId());
		Page<SmsSend> list = smsService.listSend(example, page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(list.getResult());
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
		SmsConfig config = smsService.selectConfig(EsSecurityHandler.clientId());
		if(config == null || config.getRemain() < mobile.split(",").length) {
			throw new EsRuntimeException(ErrorConstants.SMS_NOT_ENOUGH);
		}
		String tkey = DateUtil.getDate("yyyyMMddHHmmss");
		String smsUsername = dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_SMS_CONFIG, Constants.SMS_CONFIG_USERNAME).getName();
		String smsPassword = dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_SMS_CONFIG, Constants.SMS_CONFIG_PASSWORD).getName();
		String passwd = MD5Util.str2Base32MD5(MD5Util.str2Base32MD5(smsPassword) + tkey);
		
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
		
		if("1".equals(response.getBody().split(",")[0])){
		    String[] mobiles = mobile.split(",");
			for(int i=0;i<mobiles.length;i++) {
                String m = mobiles[i];
				SmsSend smsSend = new SmsSend();
				smsSend.setContent(content);
				smsSend.setMobile(m.trim());
				smsSend.setMsgid(response.getBody().split(",")[1] + "-" + (i + 1));
				smsSend.setTime(DateUtil.genTime(new Date()));
				smsSend.setXh(xh);
				smsService.send(smsSend);
			}
			return JSONResult.success(response.getBody());
		}else {
			return JSONResult.failure("E999", response.getBody());
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
		SmsConfig config = smsService.selectConfig(EsSecurityHandler.clientId());
		if(config == null || config.getRemain() < mobile.split(",").length) {
			throw new EsRuntimeException(ErrorConstants.SMS_NOT_ENOUGH);
		}
		String tkey = DateUtil.getDate("yyyyMMddHHmmss");
		String smsUsername = dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_SMS_CONFIG, Constants.SMS_CONFIG_USERNAME).getName();
		String smsPassword = dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_SMS_CONFIG, Constants.SMS_CONFIG_PASSWORD).getName();
		String passwd = MD5Util.str2Base32MD5(MD5Util.str2Base32MD5(smsPassword) + tkey);
		
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
		
		if("1".equals(response.getBody().split(",")[0])){
			String[] mobiles = mobile.split(",");
			for(int i=0;i<mobiles.length;i++) {
				SmsSend smsSend = new SmsSend();
				smsSend.setContent(content.split("※")[i]);
				smsSend.setMobile(mobiles[i]);
				smsSend.setMsgid(response.getBody().split(",")[1] + "-" + (i + 1));
				smsSend.setTime(DateUtil.genTime(new Date()));
				smsSend.setXh(xh);
				smsService.send(smsSend);
			}
			return JSONResult.success(response.getBody());
		}else {
			return JSONResult.failure("E999", response.getBody());
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
		String smsUsername = dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_SMS_CONFIG, Constants.SMS_CONFIG_USERNAME).getName();
		String smsPassword = dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_SMS_CONFIG, Constants.SMS_CONFIG_PASSWORD).getName();
		String passwd = MD5Util.str2Base32MD5(MD5Util.str2Base32MD5(smsPassword) + tkey);
		
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

		MultiValueMap<String, String> map= new LinkedMultiValueMap<>();
		map.add("username", smsUsername);
		map.add("tkey", tkey);
		map.add("password", passwd);

		HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(map, headers);

		ResponseEntity<String> response = restTemplate.postForEntity(balanceURL, request , String.class);
		
		return JSONResult.success(response.getBody());
	}
	
	/**
	 * 接收短信回复
	 * @return
	 */
	@RequestMapping(value = "/receive", method = RequestMethod.GET)
	@ResponseBody
	public Object receive(@RequestParam String mobile, @RequestParam String content, @RequestParam String msgid, @RequestParam(required=false) String xh) {
		SmsReply smsReply = new SmsReply();
		smsReply.setContent(content);
		smsReply.setMobile(mobile);
		smsReply.setMsgid(msgid);
		smsReply.setTime(DateUtil.genTime(new Date()));
		smsReply.setXh(xh);
		smsService.reply(smsReply);
		
		SmsSend send = smsService.selectSend(msgid);
		SmsConfig config = smsService.selectConfig(send.getClientId());
		if(config != null && StringUtils.isNotEmpty(config.getReplyUrl())) {
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
		
		return JSONResult.success();
	}
	
	/**
	 * 短信回复列表
	 * @param page
	 * @param request
	 * @return
	 */
	@RequestMapping(value = "/reply/list", method = RequestMethod.POST)
	public Object listReply(@RequestBody JSONRequestPage<String> page, HttpServletRequest request) {
		SmsReply example = JSONUtil.fromJson(page.getSearch(), SmsReply.class);
		Page<SmsReply> list = smsService.listReply(example, page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(list.getResult());
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
		SmsConfig config = smsService.selectConfig(EsSecurityHandler.clientId());
		return JSONResult.success(config);
	}
	
	/**
	 * 更新短信配置信息
	 * @param config
	 * @return
	 */
	@RequestMapping(value = "/config/update", method = RequestMethod.POST)
	public Object updateConfig(@RequestBody SmsConfig config) {
		config.setClientId(EsSecurityHandler.clientId());
		smsService.updateConfig(config);
		return JSONResult.success(config);
	}
	
	/**
	 * 获取短信模板信息
	 * @param templateId
	 * @return
	 */
	@RequestMapping(value = "/template/get", method = RequestMethod.GET)
	public Object findTemplateById(@RequestParam(name = "templateId") String templateId) throws Exception {
		SmsTemplate sms = smsService.findTemplate(templateId);
		if(sms == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		return JSONResult.success(sms);
	}

	/**
	 * 创建短信模板
	 * @param sms
	 * @return
	 */
	@RequestMapping(value = "/template/create", method = RequestMethod.POST)
	public Object createTemplate(@RequestBody SmsTemplate sms) {
		smsService.createTemplate(sms);
		return JSONResult.success(sms);
	}

	/**
	 * 更新短信模板信息
	 * @param sms
	 * @return
	 */
	@RequestMapping(value = "/template/update", method = RequestMethod.POST)
	public Object updateTemplate(@RequestBody SmsTemplate sms) {
		smsService.updateTemplate(sms);
		return JSONResult.success(sms);
	}

	/**
	 * 删除短信模板
	 * @param templateId
	 * @return
	 */
	@RequestMapping(value = "/template/delete", method = RequestMethod.GET)
	public Object deleteTemplate(@RequestParam(name = "templateId") String templateId) {
		smsService.deleteTemplate(templateId);
		return JSONResult.success();
	}
	
	/**
	 * 短信模板列表
	 * @param page
	 * @param request
	 * @return
	 */
	@RequestMapping(value = "/template/list", method = RequestMethod.POST)
	public Object listTemplate(@RequestBody JSONRequestPage<String> page, HttpServletRequest request) {
		SmsTemplateExample example = JSONUtil.fromJson(page.getSearch(), SmsTemplateExample.class);
		if(example == null) {
			example = new SmsTemplateExample();
		}
		example.setClientId(EsSecurityHandler.clientId());
		Page<SmsTemplate> list = smsService.listTemplate(example, page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(list.getResult());
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
		return JSONResult.success();
	}
}
