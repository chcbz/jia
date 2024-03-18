package cn.jia.kefu.common;

import cn.jia.core.exception.EsErrorConstants;

/**
 * 客服模块异常常量
 * @author chc
 * @since 2021/2/1
 */
public class KefuErrorConstants extends EsErrorConstants {
    public KefuErrorConstants(String code, String message) {
        super(code, message);
    }

    /** 礼品不存在 */
    public static final KefuErrorConstants GIFT_NOT_EXISTS = new KefuErrorConstants("EPOINT001", "礼品不存在");
    /** 礼品数量不足 */
    public static final KefuErrorConstants GIFT_NO_ENOUGH = new KefuErrorConstants("EPOINT002", "礼品数量不足");
    /** 还没到签到时间 */
    public static final KefuErrorConstants SIGN_NO_THE_TIME = new KefuErrorConstants("EPOINT003", "还没到签到时间");
    /** 用户已经被推荐 */
    public static final KefuErrorConstants REFERRAL_EXISTS = new KefuErrorConstants("EPOINT004", "用户已经被推荐");
    /** 已支付状态才能取消 */
    public static final KefuErrorConstants GIFT_CANNOT_CANCEL = new KefuErrorConstants("EPOINT005", "已支付状态才能取消");

    /** 用户不存在 */
    public static final KefuErrorConstants USER_NOT_EXIST = new KefuErrorConstants("EUSER002", "用户不存在");

}
