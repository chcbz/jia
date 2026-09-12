package cn.jia.wx.dailyvote;

/**
 * Lookup inputs available before the Redis question pointer is read.
 */
public record WxDailyVoteReplayQuery(
        String appid,
        String messageKey,
        String userKey,
        String answer) {
}
