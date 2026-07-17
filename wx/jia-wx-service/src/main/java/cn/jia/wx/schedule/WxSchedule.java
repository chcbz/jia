package cn.jia.wx.schedule;

import cn.jia.core.entity.DelayObj;
import cn.jia.core.exception.EsErrorConstants;
import cn.jia.core.common.EsConstants;
import cn.jia.core.redis.RedisService;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.StringUtil;
import cn.jia.core.util.ValidUtil;
import cn.jia.core.util.thread.AbstractThreadRequestContent;
import cn.jia.core.util.thread.ThreadRequest;
import cn.jia.kefu.entity.KefuMsgSubscribeEntity;
import cn.jia.kefu.entity.KefuMsgTypeCode;
import cn.jia.kefu.entity.KefuMsgTypeEntity;
import cn.jia.kefu.service.KefuMsgSubscribeService;
import cn.jia.kefu.service.KefuMsgTypeService;
import cn.jia.kefu.service.KefuService;
import cn.jia.mat.entity.MatPhraseEntity;
import cn.jia.mat.entity.MatVoteItemEntity;
import cn.jia.mat.entity.MatVoteQuestionVO;
import cn.jia.mat.service.MatPhraseService;
import cn.jia.mat.service.MatVoteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

@Component
@Slf4j
public class WxSchedule {
	private static final ZoneId SCHEDULE_ZONE = ZoneId.of("Asia/Shanghai");
	private static final String SCHEDULE_LOCK_PREFIX = "wx_schedule:send_vote:";

	@Autowired(required = false)
	private MatVoteService voteService;
	@Autowired
	private RedisService redisService;
	@Autowired(required = false)
	private MatPhraseService phraseService;
	@Autowired(required = false)
	private KefuService kefuService;
	@Autowired(required = false)
	private KefuMsgSubscribeService kefuMsgSubscribeService;
	@Autowired(required = false)
	private KefuMsgTypeService kefuMsgTypeService;

	/**
	 * 每天7:30发送提问信息，同时生成每日一句处理队列
	 */
	@Scheduled(cron = "0 30 7 * * ?", zone = "Asia/Shanghai")
	public void sendVote() {
		String scheduleLockKey = SCHEDULE_LOCK_PREFIX + LocalDate.now(SCHEDULE_ZONE);
		if (!redisService.setIfAbsent(scheduleLockKey, "1", Duration.ofMinutes(15))) {
			log.info("Skip duplicate WeChat schedule. key={}", scheduleLockKey);
			return;
		}

		KefuMsgSubscribeEntity subscribe = new KefuMsgSubscribeEntity();
		subscribe.setTypeCode(KefuMsgTypeCode.VOTE.getCode());
		subscribe.setStatus(EsConstants.COMMON_ENABLE);
		subscribe.setWxRxFlag(EsConstants.COMMON_YES);
		List<KefuMsgSubscribeEntity> subscribeList = kefuMsgSubscribeService.findList(subscribe);
		for (KefuMsgSubscribeEntity kefuMsgSubscribe : subscribeList) {
			KefuMsgTypeEntity kefuMsgType =
					kefuMsgTypeService.findOne(new KefuMsgTypeEntity().setTypeCode(kefuMsgSubscribe.getTypeCode()));
			MatVoteQuestionVO question = voteService.findOneQuestion(kefuMsgSubscribe.getJiacn());
			if (kefuMsgType == null || question == null) {
				log.warn("Skip vote notice, msgType or question missing. jiacn={}", kefuMsgSubscribe.getJiacn());
				continue;
			}
			String title = question.getTitle();
			StringBuilder content = new StringBuilder();
			for (MatVoteItemEntity item : question.getItems()) {
				if (StringUtil.isNotEmpty(content)) {
					content.append("\\n");
				}
				content.append(item.getOpt()).append(". ").append(item.getContent());
			}
			try {
				boolean active = kefuService.isWxActive(kefuMsgSubscribe.getJiacn());
				boolean sendSuccess = kefuService.sendWxTemplate(kefuMsgType, kefuMsgSubscribe.getJiacn(), title,
						content.toString());
				if (sendSuccess && active) {
					redisService.set("vote_" + kefuMsgSubscribe.getJiacn(), String.valueOf(question.getId()),
							2L, TimeUnit.HOURS);
				}
			} catch (Exception e) {
				log.error("sendVote.WxMpTemplateMessage", e);
			}
		}

		//随机发送每日一句
		DelayQueue<DelayObj> delayQueue = new DelayQueue<>();
		subscribe.setTypeCode(KefuMsgTypeCode.PHRASE.getCode());
		subscribeList = kefuMsgSubscribeService.findList(subscribe);
		for (KefuMsgSubscribeEntity kefuMsgSubscribe : subscribeList) {
			long remainingMillis = Math.max(0, DateUtil.todayEnd().getTime() - System.currentTimeMillis());
			long delayMillis = ThreadLocalRandom.current().nextLong(remainingMillis + 1);
			delayQueue.offer(new DelayObj(delayMillis, JsonUtil.toJson(kefuMsgSubscribe)));
		}
		final int size = subscribeList.size();
		new ThreadRequest(new AbstractThreadRequestContent() {
			@Override
			public void doSomeThing() {
				for(int i=0; i<size; i++) {
					try {
						DelayObj delayObj = delayQueue.take();
						MatPhraseEntity phrase = phraseService.findRandom(null);
						KefuMsgSubscribeEntity kefuMsgSubscribe = JsonUtil.fromJson(delayObj.getData(),
								KefuMsgSubscribeEntity.class);
						ValidUtil.assertNotNull(kefuMsgSubscribe, EsErrorConstants.PARAMETER_INCORRECT.getCode());
						KefuMsgTypeEntity kefuMsgType =
								kefuMsgTypeService.findOne(new KefuMsgTypeEntity().setTypeCode(kefuMsgSubscribe.getTypeCode()));
						if (phrase == null || kefuMsgType == null) {
							log.warn("Skip phrase notice, msgType or phrase missing. jiacn={}", kefuMsgSubscribe.getJiacn());
							continue;
						}
						kefuService.sendWxTemplate(kefuMsgType, kefuMsgSubscribe.getJiacn(), phrase.getContent());
					} catch (Exception e) {
						log.error("WxSchedule.sendPhrase", e);
					}
				}
			}
		}).start();
	}
}
