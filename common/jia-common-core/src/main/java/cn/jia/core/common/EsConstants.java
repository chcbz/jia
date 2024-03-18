package cn.jia.core.common;

/**
 * 常量
 * 
 * @author chenzg
 *
 */
public class EsConstants {
	/** 单词拼接符 */
	public final static String NAME_SPLIT = "_";

	/** 逗号分割符 */
	public final static String COMMA = ",";

	/** 管理员状态-正常 */
	public final static Integer ADMIN_STATUS_ENABLE = 1;
	/** 管理员状态-停用 */
	public final static Integer ADMIN_STATUS_DISABLE = 0;

	/** 权限类型-链接 */
	public final static Integer PERMS_TYPE_URL = 1;
	/** 权限类型-按钮 */
	public final static Integer PERMS_TYPE_BUTTON = 2;

	/** 权限状态-正常 */
	public final static Integer PERMS_STATUS_ENABLE = 1;
	/** 权限状态-停用 */
	public final static Integer PERMS_STATUS_DISABLE = 0;

	/** 菜单状态-正常 */
	public final static Integer MENU_STATUS_ENABLE = 1;
	/** 菜单状态-停用 */
	public final static Integer MENU_STATUS_DISABLE = 0;

	/** 菜单收藏类型-首页 */
	public final static Integer MENU_FAV_TYPE_INDEX = 1;
	/** 菜单收藏类型-收藏夹 */
	public final static Integer MENU_FAV_TYPE_FAV = 2;

	/** cookie名称 */
	public final static String COOKIE_LANG = "lang";
	public final static String COOKIE_USERID = "user_id";
	public final static String COOKIE_COOKIEID = "cookie_id";
	/** cookie目录 */
	public final static String COOKIE_PATH = "/";

	public final static String WEBSOCKET_USERNAME = "websocket_username";

	/** 页面返回当前用户信息 */
	public final static String CURRENT_USER = "_user";
	/** 页面返回信息键 */
	public static final String MESSAGE = "_message";
	/** 页面返回值键 */
	public static final String RESULT = "_result";

	/** 平台-IOS */
	public final static String PLATFORM_IOS = "IOS";
	/** 平台-Android */
	public final static String PLATFORM_ANDROID = "android";

	public static final String SIGN_TYPE_RSA = "RSA";

	/**
	 * sha256WithRsa 算法请求类型
	 */
	public static final String SIGN_TYPE_RSA2 = "RSA2";

	public static final String SIGN_ALGORITHMS = "SHA1WithRSA";

	public static final String SIGN_SHA256RSA_ALGORITHMS = "SHA256WithRSA";
	
	/** 数据字典类型-异常代码 */
	public static final String DICT_TYPE_ERROR_CODE = "ERROR_CODE";
	/** 数据字典类型-模块链接地址 */
	public static final String DICT_TYPE_MODULE_URL = "MODULE_URL";
	/** 数据字典类型-jia配置 */
	public static final String DICT_TYPE_JIA_CONFIG = "JIA_CONFIG";
	/** 数据字典类型-jia服务地址 */
	public static final String JIA_CONFIG_SERVER_URL = "SERVER_URL";
	/** 数据字典类型-jia平台客户ID */
	public static final String JIA_CONFIG_CLIENT_ID = "CLIENT_ID";
	/** 数据字典类型-jia平台客户密码 */
	public static final String JIA_CONFIG_CLIENT_SECRET = "CLIENT_SECRET";
	
	/** 常见判断-是 */
	public static final Integer COMMON_YES = 1;
	/** 常见判断-否 */
	public static final Integer COMMON_NO = 0;
	
	/** 常见判断-有效 */
	public static final Integer COMMON_ENABLE = 1;
	/** 常见判断-无效 */
	public static final Integer COMMON_DISABLE = 0;

	/** 文件类型-LOGO */
	public static final Integer FILE_TYPE_LOGO = 1;
	/** 文件类型-用户头像 */
	public static final Integer FILE_TYPE_AVATAR = 2;
	/** 文件类型-公告内容图片 */
	public static final Integer FILE_TYPE_NOTICE = 3;
	/** 文件类型-自定义表单图片 */
	public static final Integer FILE_TYPE_CMS = 4;
	/** 文件类型-图文素材 */
	public static final Integer FILE_TYPE_MAT = 5;
}
