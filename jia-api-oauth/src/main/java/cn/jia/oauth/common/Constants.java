package cn.jia.oauth.common;

import cn.jia.core.common.EsConstants;

public class Constants extends EsConstants {

	/** 短信验证码类型-登录 */
	public static final Integer SMS_CODE_TYPE_LOGIN = 1;
	/** 短信验证码类型-忘记密码 */
	public static final Integer SMS_CODE_TYPE_RESETPWD = 2;
	
	/** 用户状态-正常 */
	public static final Integer USER_STATUS_WORK = 1;
	/** 用户状态-出差 */
	public static final Integer USER_STATUS_TRAVEL = 2;
	/** 用户状态-离岗 */
	public static final Integer USER_STATUS_LEAVE = 0;
	
	/** 默认角色ID */
	public static final Integer DEFAULT_ROLE_ID = 2;

	/** 短信验证码默认模板ID */
	public static final String SMS_CODE_TEMPLATE_ID = "7b54bj5g71zjd9f4z44763tgu5kijr2f";

	public static final String DICT_TYPE_SMS_CONFIG = "SMS_CONFIG";

	public static final String SMS_CONFIG_USERNAME = "USERNAME";
	public static final String SMS_CONFIG_PASSWORD = "PASSWORD";

	public static final String DEFAULT_CLIENT_ID = "jia_client";
}
