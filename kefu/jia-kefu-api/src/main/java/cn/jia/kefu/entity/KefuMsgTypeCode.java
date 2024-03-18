package cn.jia.kefu.entity;

import lombok.Getter;

/**
 * @author chc
 */

public enum KefuMsgTypeCode {
    /**
     * 每日一题
     */
    VOTE("vote", "每日一题"),
    /**
     * 定时任务
     */
    TASK("task", "定时任务"),
    /**
     * 礼品下单通知
     */
    GIFT_USAGE("gift_usage", "礼品下单通知"),
    /**
     * 礼品发货通知
     */
    GIFT_DELIVERY("gift_delivery", "礼品发货通知"),
    /**
     * 每日一句
     */
    PHRASE("phrase", "每日一句");

    @Getter
    private final String code;
    @Getter
    private final String name;

    KefuMsgTypeCode(String code, String name) {
        this.code = code;
        this.name = name;
    }
}
