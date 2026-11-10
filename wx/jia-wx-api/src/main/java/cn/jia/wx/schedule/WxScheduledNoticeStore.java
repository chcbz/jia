package cn.jia.wx.schedule;

import cn.jia.wx.schedule.WxScheduledNotice.Key;
import cn.jia.wx.schedule.WxScheduledNotice.State;

/** Business receipts in the task database. No polling, worker lease or external transport here. */
public interface WxScheduledNoticeStore {
    /** First planning wins; repeat discovery returns the original recipient and random deadline. */
    WxScheduledNotice plan(Key key, String jiacn, long dueAt);
    WxScheduledNotice find(Key key);
    /** Commit this transition before any external send; STARTED is never automatically replayed. */
    boolean start(Key key, String token, long now);
    void finish(Key key, String token, State outcome, long now);
    /** Expired or withdrawn subscription, only while no send has started. */
    boolean skip(Key key, State reason, long now);
}
