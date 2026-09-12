package cn.jia.wx.dailyvote;

/**
 * Immutable inputs for one persisted daily-vote answer attempt.
 */
public record WxDailyVoteAnswerCommand(
        String appid,
        String messageKey,
        String userKey,
        String jiacn,
        long questionId,
        String answer) {
}
