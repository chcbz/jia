package cn.jia.kefu.service.impl;

import cn.jia.core.common.EsConstants;
import cn.jia.core.redis.RedisService;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.StringUtil;
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
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class KefuServiceImpl implements KefuService {
	/**
	 * 活跃用户
	 */
	private static final String ACTIVE_MP_USER = "active_mp_user_";
	private static final String WX_USER_UNSUBSCRIBED_ERROR_CODE = "43004";
	
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
	@Autowired(required = false)
	private RedisService redisService;

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
		if (mpUser == null || EsConstants.COMMON_NO.equals(mpUser.getSubscribe())) {
			return false;
		}
		boolean active = redisService.get(ACTIVE_MP_USER + mpUser.getOpenId()) != null;
		return sendWxTemplate(kefuMsgType, mpUser, jiacn, active, attr);
	}

	@Override
	public cn.jia.kefu.entity.WxNoticeSendResult sendScheduledWxTemplate(
			String clientId, String typeCode, String jiacn, String... attr) throws Exception {
		KefuMsgTypeEntity type = kefuMsgTypeDao.findScheduledMsgType(clientId, typeCode);
		if (type == null) return new cn.jia.kefu.entity.WxNoticeSendResult(false, false, null);
		MpUserEntity user = mpUserService.findScheduledRecipient(clientId, jiacn);
		if (user == null) return new cn.jia.kefu.entity.WxNoticeSendResult(false, false, null);
		boolean active = redisService.get(ACTIVE_MP_USER + user.getOpenId()) != null;
		return new cn.jia.kefu.entity.WxNoticeSendResult(sendWxTemplate(type, user, jiacn, active, attr), active, user.getAppid());
	}

	private boolean sendWxTemplate(KefuMsgTypeEntity kefuMsgType, MpUserEntity mpUser,
			String jiacn, boolean active, String... attr) throws Exception {
		String msgContent = "";
		boolean sendSuccess = false;
		if (StringUtil.isNotEmpty(kefuMsgType.getWxTemplateTxt()) && active) {
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
				// A transport/provider exception is not proof of non-delivery. Do not issue a second message.
				markUserUnsubscribed(mpUser, e);
				throw e;
			}
		}
		// 如果客服消息发送失败，尝试用模板消息
		if (!sendSuccess && StringUtil.isNotEmpty(kefuMsgType.getWxTemplateId())) {
			MpTemplateEntity mpTemplate = mpTemplateService.get(kefuMsgType.getWxTemplateId());
			if (mpTemplate != null) {
				if (!java.util.Objects.equals(mpUser.getTenantId(), mpTemplate.getTenantId())
						|| !java.util.Objects.equals(mpUser.getClientId(), mpTemplate.getClientId())
						|| !java.util.Objects.equals(mpUser.getAppid(), mpTemplate.getAppid())) {
					throw new IllegalStateException("WeChat template does not belong to the recipient application");
				}
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
				try {
					String messageId = mpInfoService.findWxMpService(mpTemplate.getAppid()).getTemplateMsgService().sendTemplateMsg(message);
					sendSuccess = StringUtil.isNotEmpty(messageId);
				} catch (WxErrorException e) {
					markUserUnsubscribed(mpUser, e);
					throw e;
				}
			}
		}
		if (sendSuccess) {
			KefuMsgLogEntity msg = new KefuMsgLogEntity();
			msg.setType(KefuConstants.MESSAGE_TYPE_WX);
			msg.setUpdateTime(DateUtil.nowTime());
			msg.setCreateTime(DateUtil.nowTime());
			msg.setJiacn(jiacn);
			msg.setTenantId(mpUser.getTenantId());
			msg.setClientId(mpUser.getClientId());
			msg.setTitle(kefuMsgType.getWxTemplateId());
			msg.setContent(msgContent);
			msg.setUrl(kefuMsgType.getUrl());
			try {
				if (kefuMsgLogDao.insert(msg) <= 0) {
					log.warn("Wx message sent but log persistence failed. jiacn={}", jiacn);
				}
			} catch (Exception e) {
				log.error("Wx message sent but log persistence failed. jiacn={}", jiacn, e);
			}
			return true;
		}
		return false;
	}

	private void markUserUnsubscribed(MpUserEntity mpUser, WxErrorException exception) {
		if (!exception.getMessage().contains(WX_USER_UNSUBSCRIBED_ERROR_CODE) || mpUser.getId() == null) {
			return;
		}
		MpUserEntity update = new MpUserEntity();
		update.setId(mpUser.getId());
		update.setSubscribe(EsConstants.COMMON_NO);
		try {
			mpUserService.update(update);
			log.info("Marked WeChat user as unsubscribed after error 43004. jiacn={}", mpUser.getJiacn());
		} catch (Exception e) {
			log.error("Failed to mark WeChat user as unsubscribed. jiacn={}", mpUser.getJiacn(), e);
		}
	}

	@Override
	public boolean isWxActive(String jiacn) {
		MpUserEntity mpUser = mpUserService.findByJiacn(jiacn);
		return mpUser != null && redisService.get(ACTIVE_MP_USER + mpUser.getOpenId()) != null;
	}
}
