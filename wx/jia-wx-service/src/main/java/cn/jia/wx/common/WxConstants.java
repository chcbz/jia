package cn.jia.wx.common;

import cn.jia.core.common.EsConstants;

import java.util.HashMap;
import java.util.Map;

public class WxConstants extends EsConstants {

	/** 点击事件-签到 */
	public static final String EVENKEY_POINT_SIGN = "POINT_SIGN";
	/** 点击事件-我的积分 */
	public static final String EVENKEY_POINT_MINE = "POINT_MINE";
	/** 点击事件-试试手气 */
	public static final String EVENKEY_POINT_LUCK = "POINT_LUCK";
	/** 点击事件-礼品列表 */
	public static final String EVENKEY_POINT_GIFT = "POINT_GIFT";
	/** 点击事件-获取分享二维码 */
	public static final String EVENKEY_POINT_QRCODE = "POINT_QRCODE";
	
	public static final String DICT_TYPE_WX_CONFIG = "WX_CONFIG";
	
	public static final String WX_CONFIG_MP_SHARE_URL = "MP_SHARE_URL";
	public static final String WX_CONFIG_MP_LOGO_URL = "MP_LOGO_URL";
	public static final String WX_CONFIG_POINT_WEB_URL = "POINT_WEB_URL";
	public static final String WX_CONFIG_DWZ_SERVER_URL = "DWZ_SERVER_URL";

	public static final Map<String, String> WX_PAY_ORDER_PARSE = new HashMap<String, String>(){{
		put("SMS","cn.jia.sms.service.SmsPayOrderParse");
		put("GIF","cn.jia.point.service.GiftPayOrderParse");
		put("TIP","cn.jia.material.service.TipPayOrderParse");
	}};
}
