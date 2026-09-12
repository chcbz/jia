package cn.jia.wx.dailyvote;

import me.chanjar.weixin.mp.bean.message.WxMpXmlMessage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class WxDailyVoteKeys {

    private WxDailyVoteKeys() {
    }

    public static String messageKey(String appid, WxMpXmlMessage message) {
        Object msgId = message.getMsgId();
        if (msgId != null) {
            return digest("wx-message-id-v1", appid, String.valueOf(msgId));
        }
        return digest("wx-message-fallback-v1", appid, message.getToUser(), message.getFromUser(),
                String.valueOf(message.getCreateTime()), message.getMsgType(), message.getEvent(),
                message.getEventKey(), message.getContent());
    }

    public static String userKey(String appid, String jiacn) {
        return digest("wx-daily-vote-user-v1", appid, jiacn);
    }

    public static String requestFingerprint(String appid, String userKey, long questionId, String answer) {
        return digest("wx-daily-vote-answer-v1", appid, userKey, String.valueOf(questionId), answer);
    }

    public static String trace(String messageKey) {
        return messageKey == null || messageKey.length() < 12 ? "none" : messageKey.substring(0, 12);
    }

    private static String digest(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = part == null ? new byte[0] : part.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
