package cn.jia.point.common;

import cn.jia.core.common.EsConstants;

public class PointConstants extends EsConstants {

	/** 分值场景-新用户 */
	public static final int POINT_SCORE_INIT = 5;
	/** 分值场景-签到 */
	public static final int POINT_SCORE_SIGN = 1;
	/** 分值场景-推荐 */
	public static final int POINT_SCORE_REFERRAL = 3;
	
	/** 积分类型-新用户 */
	public static final int POINT_TYPE_INIT = 1;
	/** 积分类型-签到 */
	public static final int POINT_TYPE_SIGN = 2;
	/** 积分类型-推荐 */
	public static final int POINT_TYPE_REFERRAL = 3;
	/** 积分类型-礼品兑换 */
	public static final int POINT_TYPE_REDEEM = 4;
	/** 积分类型-试试手气 */
	public static final int POINT_TYPE_LUCK = 5;
	/** 积分类型-投票 */
	public static final int POINT_TYPE_VOTE = 6;
	/** 积分类型-短语被赞 */
	public static final int POINT_TYPE_PHRASE = 7;

	/** 订单状态-未支付 */
	public static final Integer GIFT_USAGE_STATUS_DRAFT = 0;
	/** 订单状态-已支付 */
	public static final Integer GIFT_USAGE_STATUS_PAYED = 1;
	/** 订单状态-已取消 */
	public static final Integer GIFT_USAGE_STATUS_CANCEL = 5;
	
}
