package cn.jia.wx.common;

import cn.jia.core.exception.EsErrorConstants;

/**
 * 错误常量
 * @author chc
 * @since 2017年12月8日 下午2:47:56
 */
public class WxErrorConstants extends EsErrorConstants {
	public WxErrorConstants(String code, String message) {
		super(code, message);
	}
	
	/** 域名不存在 */
	public static final WxErrorConstants DOMAIN_NOT_EXIST = new WxErrorConstants("EOA001", "域名不存在");
	/** 密钥不正确 */
	public static final WxErrorConstants KEY_INCORRECT = new WxErrorConstants("EOA002", "密钥不正确");
	
	public static final WxErrorConstants POINT_SIGN_SUCCESS = new WxErrorConstants("EOA002", "签到成功");
	
	/** 用户不存在 */
	public static final WxErrorConstants USER_NOT_EXIST = new WxErrorConstants("EUSER002", "用户不存在");
	
	public static final Integer WX_ERRCODE_SUCCESS = 0;
	
	/** appid不能为空 */
	public static final WxErrorConstants APPID_NOT_NULL = new WxErrorConstants("EWX001", "appid不能为空");
	/** 找不到公众号 */
	public static final WxErrorConstants WXMP_NOT_EXIST = new WxErrorConstants("EWX002", "找不到公众号");
	/** 支付订单类型为空 */
	public static final WxErrorConstants WXPAY_TYPE_ISNULL = new WxErrorConstants("EWX003", "支付订单类型为空");
	/** 找不到支付订单处理类 */
	public static final WxErrorConstants WXPAY_ORDER_HANDLER_ISNULL = new WxErrorConstants("EWX004", "找不到支付订单处理类");

}
