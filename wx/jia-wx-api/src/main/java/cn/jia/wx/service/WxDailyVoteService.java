package cn.jia.wx.service;

import cn.jia.wx.dailyvote.WxDailyVoteAnswerCommand;
import cn.jia.wx.dailyvote.WxDailyVoteAnswerResult;
import cn.jia.wx.dailyvote.WxDailyVoteReplayQuery;

import java.util.Optional;

public interface WxDailyVoteService {

    Optional<WxDailyVoteAnswerResult> findReplay(WxDailyVoteReplayQuery query);

    WxDailyVoteAnswerResult answer(WxDailyVoteAnswerCommand command);
}
