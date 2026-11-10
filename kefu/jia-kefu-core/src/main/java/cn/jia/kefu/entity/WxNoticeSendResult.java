package cn.jia.kefu.entity;

/** Provider acknowledgement and the activity observation used for this exact recipient/send. */
public record WxNoticeSendResult(boolean sent, boolean active, String appid) { }
