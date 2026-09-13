package cn.jia.wx.mapper;

import cn.jia.wx.entity.WxDailyVoteMessageReceiptEntity;
import cn.jia.wx.entity.WxDailyVoteReceiptEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface WxDailyVoteReceiptMapper extends BaseMapper<WxDailyVoteReceiptEntity> {

    int claim(WxDailyVoteReceiptEntity receipt);

    List<WxDailyVoteReceiptEntity> lockConflicts(
            @Param("appid") String appid,
            @Param("messageKey") String messageKey,
            @Param("userKey") String userKey,
            @Param("questionId") long questionId);

    int claimMessage(WxDailyVoteMessageReceiptEntity messageReceipt);

    WxDailyVoteMessageReceiptEntity selectMessage(
            @Param("appid") String appid,
            @Param("messageKey") String messageKey);

    WxDailyVoteMessageReceiptEntity lockMessage(
            @Param("appid") String appid,
            @Param("messageKey") String messageKey);

    WxDailyVoteReceiptEntity selectCompletedByMessage(
            @Param("appid") String appid,
            @Param("messageKey") String messageKey);

    WxDailyVoteReceiptEntity lockById(@Param("id") long id);

    int complete(
            @Param("id") long id,
            @Param("processorToken") String processorToken,
            @Param("requestFingerprint") String requestFingerprint,
            @Param("correct") int correct,
            @Param("pointAwarded") int pointAwarded,
            @Param("replyContent") String replyContent,
            @Param("updateTime") long updateTime);
}
