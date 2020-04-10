package cn.jia.user.common;

import cn.jia.core.common.EsConstants;

public class Constants extends EsConstants {
	
	/** 短信类型-验证码 */
	public static final Integer SMS_TYPE_CODE = 1;
	
	/** 用户状态-正常 */
	public static final Integer USER_STATUS_WORK = 1;
	/** 用户状态-出差 */
	public static final Integer USER_STATUS_TRAVEL = 2;
	/** 用户状态-离岗 */
	public static final Integer USER_STATUS_LEAVE = 0;
	
	/** 默认角色ID */
	public static final Integer DEFAULT_ROLE_ID = 2;

	/** 消息状态-未读 */
	public static final Integer MSG_STATUS_UNREAD = 1;
	/** 消息状态-已读 */
	public static final Integer MSG_STATUS_READED = 2;
	/** 消息状态-已删除 */
	public static final Integer MSG_STATUS_DELETE = 0;
}
