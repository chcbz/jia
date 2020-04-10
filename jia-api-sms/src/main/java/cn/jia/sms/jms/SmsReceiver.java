package cn.jia.sms.jms;

import cn.jia.core.common.EsHandler;
import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.service.DictService;
import cn.jia.core.util.*;
import cn.jia.sms.common.Constants;
import cn.jia.sms.common.ErrorConstants;
import cn.jia.sms.dao.SmsConfigMapper;
import cn.jia.sms.dao.SmsMessageMapper;
import cn.jia.sms.dao.SmsSendMapper;
import cn.jia.sms.dao.SmsTemplateMapper;
import cn.jia.sms.entity.SmsConfig;
import cn.jia.sms.entity.SmsMessage;
import cn.jia.sms.entity.SmsSend;
import cn.jia.sms.entity.SmsTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
@RabbitListener(queues = "jia.sms")
@Slf4j
public class SmsReceiver {

	@Autowired
	private SmsMessageMapper smsMessageMapper;
	@Autowired
	private SmsSendMapper smsSendMapper;
	@Autowired
	private SmsTemplateMapper smsTemplateMapper;
	@Autowired
	private SmsConfigMapper smsConfigMapper;
	@Autowired
	private DictService dictService;
	@Autowired
	@Qualifier("singleRestTemplate")
	private RestTemplate restTemplate;

	@RabbitHandler
    public void process(String msg) {
		try {
			log.info(msg);
			SmsMessage smsMessage = JSONUtil.fromJson(msg, SmsMessage.class);
			if(smsMessage == null) {
				throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
			}
			//检查token是否有效
			String jiaUrl = dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_JIA_CONFIG, Constants.JIA_CONFIG_SERVER_URL).getName();
			
			@SuppressWarnings("unchecked")
			Map<String, Object> tokenResult = restTemplate.getForObject(jiaUrl + "/oauth/user?access_token=" + smsMessage.getToken(), Map.class);
			if(tokenResult == null || tokenResult.get("name") == null) {
				throw new EsRuntimeException(ErrorConstants.UNAUTHORIZED);
			}
			String clientId = EsSecurityHandler.clientId(smsMessage.getToken());
			
			//保存发送记录
			long now = DateUtil.genTime(new Date());
			smsMessage.setClientId(clientId);
			smsMessage.setStatus(Constants.COMMON_DISABLE);
			smsMessage.setTime(now);
			smsMessageMapper.insertSelective(smsMessage);

			if(Constants.MSG_TYPE_MAIL.equals(smsMessage.getMsgType())) {
			    String title = smsMessage.getTitle();
			    String content = smsMessage.getContent();
			    //如果已经设置了模板，按照模板进行发送，否则直接发送内容
				SmsTemplate template = smsTemplateMapper.selectByPrimaryKey(smsMessage.getTemplateId());
				if(template != null) {
                    title = template.getTitle();
					content = template.getContent();
					Map<String, Object> contentMap = JSONUtil.jsonToMap(smsMessage.getContent());
                    if(contentMap != null) {
                        for (String key : contentMap.keySet()) {
							title = title.replace("{" + key + "}", String.valueOf(contentMap.get(key)));
                            content = content.replace("{" + key + "}", String.valueOf(contentMap.get(key)));
                        }
                    }
				}

				String from = dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_EMAIL_SERVER, Constants.EMAIL_SERVER_FROM).getName();
				String name = dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_EMAIL_SERVER, Constants.EMAIL_SERVER_NAME).getName();
				String password = dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_EMAIL_SERVER, Constants.EMAIL_SERVER_PASSWORD).getName();
				String smtp = dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_EMAIL_SERVER, Constants.EMAIL_SERVER_SMTP).getName();
				if(EmailUtil.doSend(title, content, from, smsMessage.getReceiver(), name, password, smtp)) {
					SmsMessage upSmsMessage = new SmsMessage();
					upSmsMessage.setId(smsMessage.getId());
					upSmsMessage.setStatus(Constants.COMMON_ENABLE);
					smsMessageMapper.updateByPrimaryKeySelective(upSmsMessage);
				}
			}
			else if(Constants.MSG_TYPE_SMS.equals(smsMessage.getMsgType())) {
				String tkey = DateUtil.getDate("yyyyMMddHHmmss");
				String smsUsername = dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_SMS_CONFIG, Constants.SMS_CONFIG_USERNAME).getName();
				String smsPassword = dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_SMS_CONFIG, Constants.SMS_CONFIG_PASSWORD).getName();
				String passwd = MD5Util.str2Base32MD5(MD5Util.str2Base32MD5(smsPassword) + tkey);
				
				String mobile = smsMessage.getReceiver();
				SmsTemplate template = smsTemplateMapper.selectByPrimaryKey(smsMessage.getTemplateId());
				if(template == null) {
					throw new EsRuntimeException(ErrorConstants.SMS_TEMPLATE_NOT_EXIST);
				}
				String content = template.getContent();
				Map<String, Object> contentMap = JSONUtil.jsonToMap(smsMessage.getContent());
				if(contentMap != null) {
					for (String key : contentMap.keySet()) {
						content = content.replace("{" + key + "}", String.valueOf(contentMap.get(key)));
					}
				}
				SmsConfig config = smsConfigMapper.selectByPrimaryKey(clientId);
				content = "【" + config.getShortName() + "】" + content + StringUtils.ignoreNull(smsMessage.getUrl());
				
				HttpHeaders headers = new HttpHeaders();
				headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

				MultiValueMap<String, String> map= new LinkedMultiValueMap<>();
				map.add("username", smsUsername);
				map.add("tkey", tkey);
				map.add("password", passwd);
				map.add("mobile", mobile);
				map.add("content", content);

				HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(map, headers);

				String sendSmsBatchURL = "http://hy.mix2.zthysms.com/sendSmsBatch.do";
				ResponseEntity<String> response = restTemplate.postForEntity(sendSmsBatchURL, request , String.class);
				
				if("1".equals(response.getBody().split(",")[0])){
					for(String m : mobile.split(",")) {
						SmsSend smsSend = new SmsSend();
						smsSend.setClientId(clientId);
						smsSend.setContent(content);
						smsSend.setMobile(m);
						smsSend.setMsgid(response.getBody().split(",")[1]);
						smsSend.setTime(DateUtil.genTime(new Date()));
						smsSendMapper.insertSelective(smsSend);
						//设置最新剩余数量
						SmsConfig upConfig = new SmsConfig();
						upConfig.setClientId(smsSend.getClientId());
						upConfig.setRemain(config.getRemain() - 1);
						smsConfigMapper.updateByPrimaryKeySelective(upConfig);
					}
					SmsMessage upSmsMessage = new SmsMessage();
					upSmsMessage.setId(smsMessage.getId());
					upSmsMessage.setStatus(Constants.COMMON_ENABLE);
					smsMessageMapper.updateByPrimaryKeySelective(upSmsMessage);
				}else {
					log.error(response.getBody());
				}
			}
			else if(Constants.MSG_TYPE_WX.equals(smsMessage.getMsgType())) {
				Map<String, Object> message = new HashMap<>();
				message.put("toUser", smsMessage.getReceiver());
				message.put("templateId", smsMessage.getTemplateId());
				message.put("data", JSONUtil.jsonToList(smsMessage.getContent()));
				message.put("url", smsMessage.getUrl());
				
				@SuppressWarnings("unchecked")
				JSONResult<Map<String, Object>> result = restTemplate.postForObject(jiaUrl + "/wx/mp/message/template/send?appid="+smsMessage.getAppid(), EsHandler.genRestEntity(message, smsMessage.getToken()), JSONResult.class);
				if(ErrorConstants.SUCCESS.equals(result.getCode())) {
					SmsMessage upSmsMessage = new SmsMessage();
					upSmsMessage.setId(smsMessage.getId());
					upSmsMessage.setStatus(Constants.COMMON_ENABLE);
					smsMessageMapper.updateByPrimaryKeySelective(upSmsMessage);
				}else {
					log.error(result.getMsg());
				}
			}
		} catch(Exception e) {
			log.error("SmsReceiver", e);
		}
    }
}
