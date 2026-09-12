package cn.jia.wx.dailyvote;

/**
 * Persisted reply returned for both first processing and safe replay.
 */
public record WxDailyVoteAnswerResult(
        long receiptId,
        long questionId,
        String replyContent,
        boolean correct,
        int pointAwarded,
        boolean firstProcessing) {
}
