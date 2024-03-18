package cn.jia.kefu.service.impl;

import cn.jia.core.common.EsConstants;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.StringUtils;
import cn.jia.kefu.common.KefuConstants;
import cn.jia.kefu.dao.KefuMsgLogDao;
import cn.jia.kefu.dao.KefuMsgSubscribeDao;
import cn.jia.kefu.dao.KefuMsgTypeDao;
import cn.jia.kefu.entity.KefuMsgLogEntity;
import cn.jia.kefu.entity.KefuMsgSubscribeEntity;
import cn.jia.kefu.entity.KefuMsgTypeCode;
import cn.jia.kefu.entity.KefuMsgTypeEntity;
import cn.jia.kefu.service.KefuService;
import cn.jia.wx.entity.MpTemplateEntity;
import cn.jia.wx.entity.MpUserEntity;
import cn.jia.wx.service.MpInfoService;
import cn.jia.wx.service.MpTemplateService;
import cn.jia.wx.service.MpUserService;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.api.WxConsts;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.bean.kefu.WxMpKefuMessage;
import me.chanjar.weixin.mp.bean.template.WxMpTemplateData;
import me.chanjar.weixin.mp.bean.template.WxMpTemplateMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class KefuServiceImpl implements KefuService {
	/**
	 * 活跃用户
	 */
	private static final String ACTIVE_MP_USER = "active_mp_user_";
	
	@Autowired
	private KefuMsgTypeDao kefuMsgTypeDao;
	@Autowired
	private KefuMsgSubscribeDao kefuMsgSubscribeDao;
	@Autowired
	private KefuMsgLogDao kefuMsgLogDao;
	@Autowired(required = false)
	private MpInfoService mpInfoService;
	@Autowired(required = false)
	private MpTemplateService mpTemplateService;
	@Autowired(required = false)
	private MpUserService mpUserService;
	@Autowired
	private RedisTemplate<String, Object> redisTemplate;

	/**
	 * 发送客服消息
	 *
	 * @param msgType 消息类型
	 * @param clientId 应用标识符
	 * @param attr 属性
	 * @return 发送结果
	 * @throws Exception 异常
	 */
	@Override
	public Boolean sendMessage(KefuMsgTypeCode msgType, String clientId, String... attr) throws Exception {
		KefuMsgTypeEntity kefuMsgType = kefuMsgTypeDao.findMsgType(msgType.getCode());
		if (kefuMsgType == null) {
			return false;
		}

		// 通知订阅者
		KefuMsgSubscribeEntity example = new KefuMsgSubscribeEntity();
		example.setClientId(clientId);
		example.setTypeCode(msgType.getCode());
		List<KefuMsgSubscribeEntity> kefuMsgSubscribeList = kefuMsgSubscribeDao.selectByEntity(example);
		for (KefuMsgSubscribeEntity item : kefuMsgSubscribeList) {
			sendTemplate(kefuMsgType, item, attr);
		}
		return true;
	}

	@Override
	public void sendTemplate(KefuMsgTypeCode kefuMsgTypeCode, String jiacn, String... attr) throws Exception {
		KefuMsgTypeEntity kefuMsgType = kefuMsgTypeDao.findMsgType(kefuMsgTypeCode.getCode());
		KefuMsgSubscribeEntity item = kefuMsgSubscribeDao.findMsgSubscribe(kefuMsgTypeCode.getCode(), jiacn);
		sendTemplate(kefuMsgType, item, attr);
	}

	@Override
	public void sendTemplate(KefuMsgTypeEntity kefuMsgType, KefuMsgSubscribeEntity item, String... attr) throws Exception {
		if (EsConstants.COMMON_YES.equals(item.getWxRxFlag())) {
			sendWxTemplate(kefuMsgType, item.getJiacn(), attr);
		}
	}

	@Override
	public boolean sendWxTemplate(KefuMsgTypeEntity kefuMsgType, String jiacn, String... attr) throws Exception {
		MpUserEntity mpUser = mpUserService.findByJiacn(jiacn);
		if (mpUser == null) {
			return false;
		}
		String msgContent = "";
		boolean sendSuccess = false;
		Object activeMpUser = redisTemplate.opsForValue().get(ACTIVE_MP_USER + mpUser.getOpenId());
		if (StringUtils.isNotEmpty(kefuMsgType.getWxTemplateTxt()) && activeMpUser != null) {
			WxMpKefuMessage kfmessage = new WxMpKefuMessage();
			kfmessage.setToUser(mpUser.getOpenId());
			kfmessage.setMsgType(WxConsts.KefuMsgType.TEXT);
			msgContent = kefuMsgType.getWxTemplateTxt();
			for (int i = 0; i < attr.length; i++) {
				msgContent = msgContent.replace("#" + i + "#", attr[i]);
			}
			kfmessage.setContent(msgContent.replaceAll("\\\\n", "\n"));
			try {
				sendSuccess = mpInfoService.findWxMpService(mpUser.getAppid()).getKefuService().sendKefuMessage(kfmessage);
			} catch (WxErrorException e) {
				log.error("sendKefuMessage error: ", e);
			}
		}
		// 如果客服消息发送失败，尝试用模板消息
		if (!sendSuccess && StringUtils.isNotEmpty(kefuMsgType.getWxTemplateId())) {
			MpTemplateEntity mpTemplate = mpTemplateService.get(kefuMsgType.getWxTemplateId());
			if (mpTemplate != null) {
				msgContent = kefuMsgType.getWxTemplate();
				for (int i = 0; i< attr.length; i++) {
					msgContent = msgContent.replace("#" + i + "#", attr[i]);
				}
				List<WxMpTemplateData> data = JsonUtil.jsonToList(msgContent, WxMpTemplateData.class);
				WxMpTemplateMessage message = new WxMpTemplateMessage();
				message.setToUser(mpUser.getOpenId());
				message.setTemplateId(mpTemplate.getTemplateId());
				message.setData(data);
				message.setUrl(kefuMsgType.getUrl());
				String messageId = mpInfoService.findWxMpService(mpTemplate.getAppid()).getTemplateMsgService().sendTemplateMsg(message);
				sendSuccess = StringUtils.isNotEmpty(messageId);
			}
		}
		if (sendSuccess) {
			KefuMsgLogEntity msg = new KefuMsgLogEntity();
			msg.setType(KefuConstants.MESSAGE_TYPE_WX);
			msg.setUpdateTime(DateUtil.nowTime());
			msg.setCreateTime(DateUtil.nowTime());
			msg.setJiacn(jiacn);
			msg.setTitle(kefuMsgType.getWxTemplateId());
			msg.setContent(msgContent);
			msg.setUrl(kefuMsgType.getUrl());
			return kefuMsgLogDao.insert(msg) > 0;
		}
		return false;
	}
}
