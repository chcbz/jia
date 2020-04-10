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
import cn.jia.sms.service.SmsPayOrderParse;
import cn.jia.sms.service.SmsService;
import com.github.pagehelper.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/sms")
public class SmsController {
	
	@Autowired
	private SmsService smsService;
	@Autowired
	private DictService dictService;

	private RestTemplate restTemplate = new RestTemplate();
	
	private static String sendSmsURL = "http://hy.mix2.zthysms.com/sendSms.do";
	private static String sendSmsBatchURL = "http://hy.mix2.zthysms.com/sendSmsBatch.do";
	private static String sendSmsBatchIdentityURL = "http://hy.mix2.zthysms.com/sendSmsBatchIdentity.do";
	private static String balanceURL = "http://hy.mix2.zthysms.com/balance.do";
	
	/**
	 * 获取短信验证码信息
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('sms-get')")
	@RequestMapping(value = "/get", method = RequestMethod.GET)
	public Object findById(@RequestParam(name = "id", required = true) Integer id) throws Exception {
		SmsCode sms = smsService.find(id);
		return JSONResult.success(sms);
	}

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
		SmsCode code = smsService.selectSmsCodeNoUsed(phone, smsType, EsSecurityHandler.clientId());
		if(code == null || !smsCode.equals(code.getSmsCode())) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		smsService.useSmsCode(code.getId());
		return JSONResult.success();
	}

	/**
	 * 提取并使用验证码
	 * @param phone
	 * @param smsType
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('sms-use')")
	@RequestMapping(value = "/use", method = RequestMethod.GET)
	public Object useSmsCode(@RequestParam String phone, @RequestParam Integer smsType) throws Exception {
		SmsCode code = smsService.selectSmsCodeNoUsed(phone, smsType, EsSecurityHandler.clientId());
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
		
		String smsCode = smsService.upsert(phone, smsType, EsSecurityHandler.clientId());

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
			String clientId = EsSecurityHandler.clientId();
			smsSend.setClientId(clientId);
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
	@PreAuthorize("hasAuthority('sms-send')")
	@RequestMapping(value = "/send", method = RequestMethod.POST)
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
			String clientId = EsSecurityHandler.clientId();
			smsSend.setClientId(clientId);
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
	@PreAuthorize("hasAuthority('sms-send_list')")
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
	 * 根据电话号码统计短信发送量
	 * @param example 过滤条件
	 * @return 统计结果
	 */
	@PreAuthorize("hasAuthority('sms-send_chart_mobile')")
	@RequestMapping(value = "/send/chart/mobile", method = RequestMethod.POST)
	public Object chartSendByMobile(@RequestBody SmsSendExample example) {
		if(example == null) {
			example = new SmsSendExample();
		}
		example.setClientId(EsSecurityHandler.clientId());
		List<Map<String, Object>> list = smsService.countSendByMobile(example);
		return JSONResult.success(list);
	}
	
	/**
	 * 批量发送短信
	 * @param mobile 手机号码
	 * @param content 短信内容
	 * @param xh 扩展的小号
	 * @return
	 */
	@PreAuthorize("hasAuthority('sms-sendBatch')")
	@RequestMapping(value = "/sendBatch", method = RequestMethod.POST)
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
				smsSend.setMobile(m);
				smsSend.setMsgid(response.getBody().split(",")[1] + "-" + (i + 1));
				smsSend.setTime(DateUtil.genTime(new Date()));
				smsSend.setXh(xh);
				String clientId = EsSecurityHandler.clientId();
				smsSend.setClientId(clientId);
				smsService.send(smsSend);
			}
			return JSONResult.success(response.getBody());
		}else {
			return JSONResult.failure("E999", response.getBody());
		}
	}
	
	/**
	 * 批量个性化短信
	 * @param mobile 手机号码
	 * @param content 短信内容
	 * @param xh 扩展的小号
	 * @return
	 */
	@PreAuthorize("hasAuthority('sms-sendSmsBatchIdentity')")
	@RequestMapping(value = "/sendSmsBatchIdentity", method = RequestMethod.POST)
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
				String clientId = EsSecurityHandler.clientId();
				smsSend.setClientId(clientId);
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
	@PreAuthorize("hasAuthority('sms-balance')")
	@RequestMapping(value = "/balance", method = RequestMethod.GET)
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

		HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<MultiValueMap<String, String>>(map, headers);

		ResponseEntity<String> response = restTemplate.postForEntity(balanceURL, request , String.class);
		
		return JSONResult.success(response.getBody());
	}
	
	/**
	 * 接收短信回复
	 * @return
	 */
	@PreAuthorize("hasAuthority('sms-receive')")
	@RequestMapping(value = "/receive", method = RequestMethod.GET)
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
	@PreAuthorize("hasAuthority('sms-reply_list')")
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
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('sms-config_get')")
	@RequestMapping(value = "/config/get", method = RequestMethod.GET)
	public Object findConfig() throws Exception {
		SmsConfig config = smsService.selectConfig(EsSecurityHandler.clientId());
		return JSONResult.success(config);
	}
	
	/**
	 * 更新短信配置信息
	 * @param config
	 * @return
	 */
	@PreAuthorize("hasAuthority('sms-config_update')")
	@RequestMapping(value = "/config/update", method = RequestMethod.POST)
	public Object updateConfig(@RequestBody SmsConfig config) {
		config.setClientId(EsSecurityHandler.clientId());
		smsService.updateConfig(config);
		return JSONResult.success(config);
	}
	
	/**
	 * 注册短信服务
	 * @return
	 */
	@RequestMapping(value = "/register", method = RequestMethod.POST)
	public Object register(@RequestBody SmsConfig config) {
		//新增客户端资源
//		JSONResult<Map<String, Object>> resourceResult = oAuthService.addResource(resourceId);
//		if(!ErrorConstants.SUCCESS.equals(resourceResult.getCode())) {
//			throw new EsRuntimeException(resourceResult.getCode());
//		}
		//新增配置信息
		config.setClientId(EsSecurityHandler.clientId());
		smsService.createConfig(config);
		return JSONResult.success(config);
	}
	
	/**
	 * 获取短信模板信息
	 * @param templateId
	 * @return
	 */
	@PreAuthorize("hasAuthority('sms-template_get')")
	@RequestMapping(value = "/template/get", method = RequestMethod.GET)
	public Object findTemplateById(@RequestParam(name = "templateId", required = true) String templateId) throws Exception {
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
	@PreAuthorize("hasAuthority('sms-template_create')")
	@RequestMapping(value = "/template/create", method = RequestMethod.POST)
	public Object createTemplate(@RequestBody SmsTemplate sms) {
		sms.setClientId(EsSecurityHandler.clientId());
		smsService.createTemplate(sms);
		return JSONResult.success(sms);
	}

	/**
	 * 更新短信模板信息
	 * @param sms
	 * @return
	 */
	@PreAuthorize("hasAuthority('sms-template_update')")
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
	@PreAuthorize("hasAuthority('sms-template_delete')")
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
	@PreAuthorize("hasAuthority('sms-template_list')")
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
	 * @param packageId
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('sms-buy_create')")
	@RequestMapping(value = "/buy/create", method = RequestMethod.GET)
	public Object buy(@RequestParam Integer packageId) throws Exception {
		SmsBuy smsBuy = smsService.buy(packageId, EsSecurityHandler.clientId());
		Map<String, Object> result = new HashMap<>();
		result.put("productId", SmsPayOrderParse.genProductId(smsBuy.getId()));
		result.put("buyId", smsBuy.getId());
		return JSONResult.success(result);
	}

	/**
	 * 获取短信套餐购买情况
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('sms-buy_get')")
	@RequestMapping(value = "/buy/get", method = RequestMethod.GET)
	public Object findBuy(@RequestParam Integer id) {
		return JSONResult.success(smsService.findBuy(id));
	}

	/**
	 * 获取商品ID
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('sms-buy_pay')")
	@RequestMapping(value = "/buy/pay", method = RequestMethod.GET)
	public Object findBuyProductId(@RequestParam Integer id) {
		return JSONResult.success(SmsPayOrderParse.genProductId(id));
	}

    @PreAuthorize("hasAuthority('sms-buy_cancel')")
    @RequestMapping(value = "/buy/cancel", method = RequestMethod.GET)
    public Object cancelBuy(@RequestParam Integer id) {
	    smsService.cancelBuy(id);
        return JSONResult.success();
    }

	/**
	 * 短信购买列表
	 * @param page
	 * @param request
	 * @return
	 */
	@PreAuthorize("hasAuthority('sms-buy_list')")
	@RequestMapping(value = "/buy/list", method = RequestMethod.POST)
	public Object listBuy(@RequestBody JSONRequestPage<String> page, HttpServletRequest request) {
		SmsBuyExample example = JSONUtil.fromJson(page.getSearch(), SmsBuyExample.class);
		if(example == null) {
			example = new SmsBuyExample();
		}
		example.setClientId(EsSecurityHandler.clientId());
		Page<SmsBuy> list = smsService.listBuy(example, page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(list.getResult());
		result.setPageNum(list.getPageNum());
		result.setTotal(list.getTotal());
		return result;
	}

	/**
	 * 短信套餐列表
	 * @param page
	 * @param request
	 * @return
	 */
	@PreAuthorize("hasAuthority('sms-package_list')")
	@RequestMapping(value = "/package/list", method = RequestMethod.POST)
	public Object listPackage(@RequestBody JSONRequestPage<String> page, HttpServletRequest request) {
		SmsPackage example = JSONUtil.fromJson(page.getSearch(), SmsPackage.class);
		Page<SmsPackage> list = smsService.listPackage(example, page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(list.getResult());
		result.setPageNum(list.getPageNum());
		result.setTotal(list.getTotal());
		return result;
	}
}
