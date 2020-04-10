package cn.jia.core.exception;

/**
 * 错误常量
 * 
 * @author chenzg
 *
 */
public class EsErrorConstants {
	
	/** 没有异常 */
	public static final String SUCCESS = "E0";
	
	/** 错误键 */
	public static final String ERROR = "_error";
	/** 错误信息 */
	public static final String ERROR_MESSAGE = "_error_msg";
	/** 注册错误键 */
	public static final String REG_ERROR = "_reg_error";
	

	/** 默认错误码 */
	public static final String DEFAULT_ERROR_CODE = "E999";
	/** 默认错误信息 */
	public static final String DEFAULT_ERROR_MESSAGE = "系统未知错误,请联系管理员!";


	/** 数据不存在 */
	public static final String DATA_NOT_FOUND = "E001";
	/** 重复提交 */
	public static final String DUPLICATE_SUBMISSION = "E002";
	/** 参数异常 */
	public static final String PARAMETER_INCORRECT = "E003";
	
	/** 未授权 */
	public static final String UNAUTHORIZED = "E401";
	/** 拒绝访问 */
	public static final String FORBIDDEN = "E403";
	/** 非法访问 */
	public static final String METHOD_NOT_ALLOWED = "E405";
	/** 请求超时 */
	public static final String REQUEST_TIMEOUT = "E408";
	/** 服务异常 */
	public static final String INTERNAL_SERVER_ERROR = "E500";

}
