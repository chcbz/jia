package cn.jia.user.common;

import cn.jia.core.common.EsConstants;

public class UserConstants extends EsConstants {
	
	/** 短信类型-验证码 */
	public static final Integer SMS_TYPE_CODE = 1;
	
	/** 用户状态-正常 */
	public static final Integer USER_STATUS_WORK = 1;
	/** 用户状态-出差 */
	public static final Integer USER_STATUS_TRAVEL = 2;
	/** 用户状态-离岗 */
	public static final Integer USER_STATUS_LEAVE = 0;
	
	/** 默认角色ID */
	public static final Long DEFAULT_ROLE_ID = 2L;

	/** 消息状态-未读 */
	public static final Integer MSG_STATUS_UNREAD = 1;
	/** 消息状态-已读 */
	public static final Integer MSG_STATUS_READED = 2;
	/** 消息状态-已删除 */
	public static final Integer MSG_STATUS_DELETE = 0;

	/** 订阅类型-投票 */
	public static final String SUBSCRIBE_VOTE = "vote";

	/** 数据字典类型-系统配置项 */
	public static final String DICT_TYPE_USER_CONFIG = "USER_CONFIG";
	public static final String USER_CONFIG_JIA_SERVER_URL = "JIA_SERVER_URL";
	public static final String USER_CONFIG_JIA_CLIENT_ID = "JIA_CLIENT_ID";
	public static final String USER_CONFIG_JIA_CLIENT_SECRET = "JIA_CLIENT_SECRET";
}
