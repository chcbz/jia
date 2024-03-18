package cn.jia.sms.jms;

import cn.jia.base.service.DictService;
import cn.jia.core.common.EsHandler;
import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.*;
import cn.jia.sms.common.SmsConstants;
import cn.jia.sms.common.SmsErrorConstants;
import cn.jia.sms.dao.SmsConfigDao;
import cn.jia.sms.dao.SmsMessageDao;
import cn.jia.sms.dao.SmsSendDao;
import cn.jia.sms.dao.SmsTemplateDao;
import cn.jia.sms.entity.*;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Component
@RabbitListener(queues = "jia.sms")
@Slf4j
public class SmsReceiver {

	@Inject
	private SmsMessageDao smsMessageDao;
	@Inject
	private SmsSendDao smsSendDao;
	@Inject
	private SmsTemplateDao smsTemplateDao;
	@Inject
	private SmsConfigDao smsConfigDao;
	@Inject
	private DictService dictService;
	@Inject
	@Qualifier("singleRestTemplate")
	private RestTemplate restTemplate;

	@RabbitHandler
    public void process(String msg) {
		try {
			log.info(msg);
			SmsMessageVO smsMessage = JsonUtil.fromJson(msg, SmsMessageVO.class);
			if(smsMessage == null) {
				throw new EsRuntimeException(SmsErrorConstants.DATA_NOT_FOUND);
			}
			//检查token是否有效
			String jiaUrl = dictService.selectByTypeAndValue(SmsConstants.DICT_TYPE_JIA_CONFIG,
					SmsConstants.JIA_CONFIG_SERVER_URL).getName();
			
			@SuppressWarnings("unchecked")
			Map<String, Object> tokenResult = restTemplate.getForObject(jiaUrl +
					"/oauth/user?access_token=" + smsMessage.getToken(), Map.class);
			if(tokenResult == null || tokenResult.get("name") == null) {
				throw new EsRuntimeException(SmsErrorConstants.UNAUTHORIZED);
			}
			String clientId = EsSecurityHandler.clientId(smsMessage.getToken());
			
			//保存发送记录
			smsMessage.setClientId(clientId);
			smsMessage.setStatus(SmsConstants.COMMON_DISABLE);
			smsMessageDao.insert(smsMessage);

			if(SmsConstants.MSG_TYPE_MAIL.equals(smsMessage.getMsgType())) {
			    String title = smsMessage.getTitle();
			    String content = smsMessage.getContent();
			    //如果已经设置了模板，按照模板进行发送，否则直接发送内容
				SmsTemplateEntity template = smsTemplateDao.selectById(smsMessage.getTemplateId());
				if(template != null) {
                    title = template.getTitle();
					content = template.getContent();
					Map<String, Object> contentMap = JsonUtil.jsonToMap(smsMessage.getContent());
                    if(contentMap != null) {
                        for (String key : contentMap.keySet()) {
							title = title.replace("{" + key + "}", String.valueOf(contentMap.get(key)));
                            content = content.replace("{" + key + "}", String.valueOf(contentMap.get(key)));
                        }
                    }
				}

				String from = dictService.selectByTypeAndValue(SmsConstants.DICT_TYPE_EMAIL_SERVER, SmsConstants.EMAIL_SERVER_FROM).getName();
				String name = dictService.selectByTypeAndValue(SmsConstants.DICT_TYPE_EMAIL_SERVER, SmsConstants.EMAIL_SERVER_NAME).getName();
				String password = dictService.selectByTypeAndValue(SmsConstants.DICT_TYPE_EMAIL_SERVER, SmsConstants.EMAIL_SERVER_PASSWORD).getName();
				String smtp = dictService.selectByTypeAndValue(SmsConstants.DICT_TYPE_EMAIL_SERVER, SmsConstants.EMAIL_SERVER_SMTP).getName();
				if(EmailUtil.doSend(title, content, from, smsMessage.getReceiver(), name, password, smtp)) {
					SmsMessageEntity upSmsMessage = new SmsMessageEntity();
					upSmsMessage.setId(smsMessage.getId());
					upSmsMessage.setStatus(SmsConstants.COMMON_ENABLE);
					smsMessageDao.updateById(upSmsMessage);
				}
			}
			else if(SmsConstants.MSG_TYPE_SMS.equals(smsMessage.getMsgType())) {
				String tkey = DateUtil.getDate("yyyyMMddHHmmss");
				String smsUsername = dictService.selectByTypeAndValue(SmsConstants.DICT_TYPE_SMS_CONFIG,
						SmsConstants.SMS_CONFIG_USERNAME).getName();
				String smsPassword = dictService.selectByTypeAndValue(SmsConstants.DICT_TYPE_SMS_CONFIG, SmsConstants.SMS_CONFIG_PASSWORD).getName();
				String passwd = Md5Util.str2Base32Md5(Md5Util.str2Base32Md5(smsPassword) + tkey);
				
				String mobile = smsMessage.getReceiver();
				SmsTemplateEntity template = smsTemplateDao.selectById(smsMessage.getTemplateId());
				if(template == null) {
					throw new EsRuntimeException(SmsErrorConstants.SMS_TEMPLATE_NOT_EXIST);
				}
				String content = template.getContent();
				Map<String, Object> contentMap = JsonUtil.jsonToMap(smsMessage.getContent());
				if(contentMap != null) {
					for (String key : contentMap.keySet()) {
						content = content.replace("{" + key + "}", String.valueOf(contentMap.get(key)));
					}
				}
				SmsConfigEntity config = smsConfigDao.selectById(clientId);
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
						SmsSendEntity smsSend = new SmsSendEntity();
						smsSend.setClientId(clientId);
						smsSend.setContent(content);
						smsSend.setMobile(m);
						smsSend.setMsgid(response.getBody().split(",")[1]);
						smsSendDao.insert(smsSend);
						//设置最新剩余数量
						SmsConfigEntity upConfig = new SmsConfigEntity();
						upConfig.setClientId(smsSend.getClientId());
						upConfig.setRemain(config.getRemain() - 1);
						smsConfigDao.updateById(upConfig);
					}
					SmsMessageEntity upSmsMessage = new SmsMessageEntity();
					upSmsMessage.setId(smsMessage.getId());
					upSmsMessage.setStatus(SmsConstants.COMMON_ENABLE);
					smsMessageDao.updateById(upSmsMessage);
				}else {
					log.error(response.getBody());
				}
			}
			else if(SmsConstants.MSG_TYPE_WX.equals(smsMessage.getMsgType())) {
				Map<String, Object> message = new HashMap<>();
				message.put("toUser", smsMessage.getReceiver());
				message.put("templateId", smsMessage.getTemplateId());
				message.put("data", JsonUtil.jsonToList(smsMessage.getContent()));
				message.put("url", smsMessage.getUrl());
				
				@SuppressWarnings("unchecked")
				JsonResult<Map<String, Object>> result = restTemplate.postForObject(
						jiaUrl + "/wx/mp/message/template/send?appid="+smsMessage.getAppid(),
						EsHandler.genRestEntity(message, smsMessage.getToken()), JsonResult.class);
				if(SmsErrorConstants.SUCCESS.getCode().equals(result.getCode())) {
					SmsMessageEntity upSmsMessage = new SmsMessageEntity();
					upSmsMessage.setId(smsMessage.getId());
					upSmsMessage.setStatus(SmsConstants.COMMON_ENABLE);
					smsMessageDao.updateById(upSmsMessage);
				}else {
					log.error(result.getMsg());
				}
			}
		} catch(Exception e) {
			log.error("SmsReceiver", e);
		}
    }
}
