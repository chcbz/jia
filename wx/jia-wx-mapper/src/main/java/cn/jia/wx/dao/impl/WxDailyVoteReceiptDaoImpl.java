package cn.jia.wx.dao.impl;

import cn.jia.wx.dao.WxDailyVoteReceiptDao;
import cn.jia.wx.entity.WxDailyVoteMessageReceiptEntity;
import cn.jia.wx.entity.WxDailyVoteReceiptEntity;
import cn.jia.wx.mapper.WxDailyVoteReceiptMapper;
import jakarta.inject.Named;

import java.util.List;

@Named
public class WxDailyVoteReceiptDaoImpl implements WxDailyVoteReceiptDao {

    private final WxDailyVoteReceiptMapper mapper;

    public WxDailyVoteReceiptDaoImpl(WxDailyVoteReceiptMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public int claim(WxDailyVoteReceiptEntity receipt) {
        return mapper.claim(receipt);
    }

    @Override
    public List<WxDailyVoteReceiptEntity> lockConflicts(
            String appid, String messageKey, String userKey, long questionId) {
        return mapper.lockConflicts(appid, messageKey, userKey, questionId);
    }

    @Override
    public int claimMessage(WxDailyVoteMessageReceiptEntity messageReceipt) {
        return mapper.claimMessage(messageReceipt);
    }

    @Override
    public WxDailyVoteMessageReceiptEntity selectMessage(String appid, String messageKey) {
        return mapper.selectMessage(appid, messageKey);
    }

    @Override
    public WxDailyVoteMessageReceiptEntity lockMessage(String appid, String messageKey) {
        return mapper.lockMessage(appid, messageKey);
    }

    @Override
    public WxDailyVoteReceiptEntity selectCompletedByMessage(String appid, String messageKey) {
        return mapper.selectCompletedByMessage(appid, messageKey);
    }

    @Override
    public WxDailyVoteReceiptEntity lockById(long id) {
        return mapper.lockById(id);
    }

    @Override
    public int complete(long id, String processorToken, String requestFingerprint, boolean correct,
                        int pointAwarded, String replyContent, long updateTime) {
        return mapper.complete(id, processorToken, requestFingerprint, correct ? 1 : 0,
                pointAwarded, replyContent, updateTime);
    }
}
