package cn.jia.point.common;

import cn.jia.core.exception.EsErrorConstants;

/**
 * 错误常量
 * @author chc
 * @since 2017年12月8日 下午2:47:56
 */
public class PointErrorConstants extends EsErrorConstants {
	public PointErrorConstants(String code, String message) {
		super(code, message);
	}
	
	/** 礼品不存在 */
	public static final PointErrorConstants GIFT_NOT_EXISTS = new PointErrorConstants("EPOINT001", "礼品不存在");
	/** 礼品数量不足 */
	public static final PointErrorConstants GIFT_NO_ENOUGH = new PointErrorConstants("EPOINT002", "礼品数量不足");
	/** 还没到签到时间 */
	public static final PointErrorConstants SIGN_NO_THE_TIME = new PointErrorConstants("EPOINT003", "还没到签到时间");
	/** 用户已经被推荐 */
	public static final PointErrorConstants REFERRAL_EXISTS = new PointErrorConstants("EPOINT004", "用户已经被推荐");
	/** 已支付状态才能取消 */
	public static final PointErrorConstants GIFT_CANNOT_CANCEL = new PointErrorConstants("EPOINT005", "已支付状态才能取消");
	/** 草稿或已取消状态才能删除 */
	public static final PointErrorConstants GIFT_CANNOT_DELETE = new PointErrorConstants("EPOINT006", "草稿或已取消状态才能删除");

}
