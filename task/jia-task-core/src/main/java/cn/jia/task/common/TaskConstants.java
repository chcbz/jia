package cn.jia.task.common;

import cn.jia.core.common.EsConstants;

public class TaskConstants extends EsConstants {

	/** 任务周期-长期 */
	public static final Integer TASK_PERIOD_ALLTIME = 0;
	/** 任务周期-年 */
	public static final Integer TASK_PERIOD_YEAR = 1;
	/** 任务周期-月 */
	public static final Integer TASK_PERIOD_MONTH = 2;
	/** 任务周期-周 */
	public static final Integer TASK_PERIOD_WEEK = 3;
	/** 任务周期-日 */
	public static final Integer TASK_PERIOD_DAY = 5;
	/** 任务周期-时 */
	public static final Integer TASK_PERIOD_HOUR = 11;
	/** 任务周期-分 */
	public static final Integer TASK_PERIOD_MINUTE = 12;
	/** 任务周期-指定日期 */
	public static final Integer TASK_PERIOD_DATE = 6;
	
	/** 是否通知-是 */
	public static final Integer TASK_REMIND_YES = 1;
	/** 是否通知-否 */
	public static final Integer TASK_REMIND_NO = 0;

	/** 任务类型-常规通知 */
	public static final Integer TASK_TYPE_NOTIFY = 1;
	
	/** 消息类型-微信 */
	public static String MESSAGE_TYPE_WX = "1";
	/** 消息类型-邮件 */
	public static String MESSAGE_TYPE_EMAIL = "2";
	/** 消息类型-短信 */
	public static String MESSAGE_TYPE_SMS = "3";
	
	/** 数据字典类型-系统配置项 */
	public static final String DICT_TYPE_TASK_CONFIG = "TASK_CONFIG";
	/** 数据字典类型-任务类型 */
	public static final String DICT_TYPE_TASK_TYPE = "TASK_TYPE";
	
	public static final String SMS_CONTENT_NOTIFY_MSG = "NOTIFY_MSG";
	
	public static final String TASK_CONFIG_NOTIFY_URL = "NOTIFY_URL";
	public static final String TASK_CONFIG_WX_MSG_TEMPLATE_ID = "WX_MSG_TEMPLATE_ID";
	public static final String TASK_CONFIG_WX_APP_ID = "WX_APP_ID";

	/** 任务计划状态-有效 */
	public static final Integer TASK_STATUS_ENABLE = 1;
	/** 任务计划状态-失效 */
	public static final Integer TASK_STATUS_DISABLE = 0;
}
