package cn.jia.wx.dao;

import cn.jia.wx.entity.WxDailyVoteMessageReceiptEntity;
import cn.jia.wx.entity.WxDailyVoteReceiptEntity;

import java.util.List;

public interface WxDailyVoteReceiptDao {

    int claim(WxDailyVoteReceiptEntity receipt);

    List<WxDailyVoteReceiptEntity> lockConflicts(String appid, String messageKey, String userKey, long questionId);

    int claimMessage(WxDailyVoteMessageReceiptEntity messageReceipt);

    WxDailyVoteMessageReceiptEntity selectMessage(String appid, String messageKey);

    WxDailyVoteMessageReceiptEntity lockMessage(String appid, String messageKey);

    WxDailyVoteReceiptEntity selectCompletedByMessage(String appid, String messageKey);

    WxDailyVoteReceiptEntity lockById(long id);

    int complete(long id, String processorToken, String requestFingerprint, boolean correct,
                 int pointAwarded, String replyContent, long updateTime);
}
