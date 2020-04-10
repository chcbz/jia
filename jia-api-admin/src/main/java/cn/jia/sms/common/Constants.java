package cn.jia.sms.common;

import cn.jia.core.common.EsConstants;

public class Constants extends EsConstants {
	
	public static final String DICT_TYPE_SMS_CONFIG = "SMS_CONFIG";
	/** 数据字典类型-邮件服务器 */
	public static final String DICT_TYPE_EMAIL_SERVER = "EMAIL_SERVER";
	
	public static final String SMS_CONFIG_USERNAME = "USERNAME";
	public static final String SMS_CONFIG_PASSWORD = "PASSWORD";
	
	/** 消息类型-微信 */
	public static final Integer MSG_TYPE_WX = 1;
	/** 消息类型-邮件 */
	public static final Integer MSG_TYPE_MAIL = 2;
	/** 消息类型-短信 */
	public static final Integer MSG_TYPE_SMS = 3;
	
	public static final String EMAIL_SERVER_FROM = "FROM";
	public static final String EMAIL_SERVER_NAME = "NAME";
	public static final String EMAIL_SERVER_PASSWORD = "PASSWORD";
	public static final String EMAIL_SERVER_SMTP = "SMTP";
	
	/** 短信验证码默认模板ID */
	public static final String SMS_CODE_TEMPLATE_ID = "7b54bj5g71zjd9f4z44763tgu5kijr2f";
	/** 短信消息默认模板ID */
	public static final String SMS_REVIEW_TEMPLATE_ID = "5snxcj6ffptr1abj5b5etecg1kuhkcdh";

	/** 短信购买状态-未支付 */
	public static final Integer SMS_BUY_STATUS_DRAFT = 0;
	/** 短信购买状态-已支付 */
	public static final Integer SMS_BUY_STATUS_PAID = 1;
	/** 短信购买状态-已取消 */
	public static final Integer SMS_BUY_STATUS_CANCEL = 2;

	/** 短信验证码类型-登录 */
	public static final Integer SMS_CODE_TYPE_LOGIN = 1;
	/** 短信验证码类型-忘记密码 */
	public static final Integer SMS_CODE_TYPE_RESETPWD = 2;
}
