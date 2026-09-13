package cn.jia.sms.service.impl;

import cn.jia.core.deadline.SafeRequestTimeoutException;
import cn.jia.core.lock.IDistributedLock;
import cn.jia.core.lock.ILock;
import cn.jia.core.util.HttpUtil;
import cn.jia.core.util.StringUtil;
import cn.jia.sms.config.SmsExternalHttpClient;
import cn.jia.sms.entity.SmsConfigEntity;
import cn.jia.sms.entity.SmsReplyEntity;
import cn.jia.sms.entity.SmsSendEntity;
import cn.jia.sms.service.SmsService;
import jakarta.inject.Named;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Idempotent inbound SMS callback coordinator. The exact callback is serialized
 * by a bounded distributed lock, downstream GET delivery is outside a database
 * transaction, and the durable reply is written only after delivery succeeds.
 */
@Named
public class SmsReplyCallbackService {
    private static final long LOCK_WAIT_MILLIS = 1_000;
    private static final long LOCK_LEASE_MILLIS = 5_000;

    private final SmsService smsService;
    private final SmsExternalHttpClient externalHttpClient;
    private final IDistributedLock distributedLock;

    public SmsReplyCallbackService(SmsService smsService, SmsExternalHttpClient externalHttpClient,
            Optional<IDistributedLock> distributedLock) {
        this.smsService = smsService;
        this.externalHttpClient = externalHttpClient;
        this.distributedLock = distributedLock.orElse(null);
    }

    public void receive(String mobile, String content, String msgid, String xh) {
        if (StringUtil.isBlank(mobile) || StringUtil.isBlank(content) || StringUtil.isBlank(msgid)) {
            throw new SmsReplyCallbackException("SMS callback fields are required");
        }
        if (distributedLock == null) {
            throw SafeRequestTimeoutException.dependencyUnavailableBeforeWork(
                    SafeRequestTimeoutException.Dependency.REDIS);
        }
        ILock callbackLock;
        try {
            callbackLock = distributedLock.tryLock(lockKey(mobile, content, msgid, xh), LOCK_WAIT_MILLIS,
                    LOCK_LEASE_MILLIS, TimeUnit.MILLISECONDS, false);
        } catch (Exception exception) {
            throw SafeRequestTimeoutException.dependencyUnavailableBeforeWork(
                    SafeRequestTimeoutException.Dependency.REDIS);
        }
        if (callbackLock == null) {
            throw SafeRequestTimeoutException.dependencyUnavailableBeforeWork(
                    SafeRequestTimeoutException.Dependency.REDIS);
        }

        try (callbackLock) {
            SmsSendEntity send = smsService.selectSend(msgid);
            if (send == null || StringUtil.isEmpty(send.getClientId())) {
                throw new SmsReplyCallbackException("SMS callback does not match an accepted send");
            }
            SmsReplyEntity exactReply = exactReply(mobile, content, msgid, xh);
            exactReply.setClientId(send.getClientId());
            exactReply.setTenantId(send.getTenantId());
            if (smsService.findReply(exactReply) != null) {
                return;
            }

            SmsConfigEntity config = smsService.selectConfig(send.getClientId());
            if (config != null && StringUtil.isNotEmpty(config.getReplyUrl())) {
                String replyUrl = callbackUrl(config.getReplyUrl(), mobile, content, msgid, xh);
                SmsExternalHttpClient.OperationBudget budget = externalHttpClient.beginOperation();
                externalHttpClient.getForObject(budget, replyUrl, String.class);
            }

            smsService.reply(exactReply);
        }
    }

    private SmsReplyEntity exactReply(String mobile, String content, String msgid, String xh) {
        SmsReplyEntity reply = new SmsReplyEntity();
        reply.setContent(content);
        reply.setMobile(mobile);
        reply.setMsgid(msgid);
        reply.setXh(xh);
        return reply;
    }

    private String callbackUrl(String baseUrl, String mobile, String content, String msgid, String xh) {
        String replyUrl = HttpUtil.addUrlValue(baseUrl, "mobile", mobile);
        replyUrl = HttpUtil.addUrlValue(replyUrl, "content", content);
        replyUrl = HttpUtil.addUrlValue(replyUrl, "msgid", msgid);
        return HttpUtil.addUrlValue(replyUrl, "xh", xh);
    }

    private String lockKey(String mobile, String content, String msgid, String xh) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, msgid);
            updateDigest(digest, mobile);
            updateDigest(digest, xh);
            updateDigest(digest, content);
            return "sms_reply_callback_" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    public static final class SmsReplyCallbackException extends RuntimeException {
        public SmsReplyCallbackException(String safeMessage) {
            super(safeMessage);
        }
    }
}
