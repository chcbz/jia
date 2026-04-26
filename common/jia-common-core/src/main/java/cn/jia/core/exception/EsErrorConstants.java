package cn.jia.core.exception;

import cn.jia.core.annotation.ErrorCodeModule;
import lombok.Getter;

/**
 * 错误常量
 * 
 * @author chenzg
 *
 */
@ErrorCodeModule("基础模块")
public class EsErrorConstants {
	@Getter
	String code;

	@Getter
	String message;

	public EsErrorConstants(String code, String message){
		this.code = code;
		this.message = message;
	}
	
	/** 没有异常 */
	public static final EsErrorConstants SUCCESS = new EsErrorConstants("E0", "没有异常");
	
	/** 错误键 */
	public static final EsErrorConstants ERROR = new EsErrorConstants("_error", "错误键");
	/** 错误信息 */
	public static final EsErrorConstants ERROR_MESSAGE = new EsErrorConstants("_error_msg", "错误信息");
	/** 注册错误键 */
	public static final EsErrorConstants REG_ERROR = new EsErrorConstants("_reg_error", "注册错误键");
	

	/** 默认错误码 */
	public static final EsErrorConstants DEFAULT_ERROR_CODE = new EsErrorConstants("E999", "默认错误码");
	/** 默认错误信息 */
	public static final EsErrorConstants DEFAULT_ERROR_MESSAGE = new EsErrorConstants("系统未知错误,请联系管理员!", "默认错误信息");


	/** 数据不存在 */
	public static final EsErrorConstants DATA_NOT_FOUND = new EsErrorConstants("E001", "数据不存在");
	/** 重复提交 */
	public static final EsErrorConstants DUPLICATE_SUBMISSION = new EsErrorConstants("E002", "重复提交");
	/** 参数异常 */
	public static final EsErrorConstants PARAMETER_INCORRECT = new EsErrorConstants("E003", "参数异常");
	/** 不是有效URL */
	public static final EsErrorConstants INVALID_URL = new EsErrorConstants("E004", "不是有效URL");
	
	/** 未授权 */
	public static final EsErrorConstants UNAUTHORIZED = new EsErrorConstants("E401", "未授权");
	/** 拒绝访问 */
	public static final EsErrorConstants FORBIDDEN = new EsErrorConstants("E403", "拒绝访问");
	/** 资源不存在 */
	public static final EsErrorConstants NOT_FOUND = new EsErrorConstants("E404", "资源不存在");
	/** 非法访问 */
	public static final EsErrorConstants METHOD_NOT_ALLOWED = new EsErrorConstants("E405", "非法访问");
	/** 请求超时 */
	public static final EsErrorConstants REQUEST_TIMEOUT = new EsErrorConstants("E408", "请求超时");
	/** 服务异常 */
	public static final EsErrorConstants INTERNAL_SERVER_ERROR = new EsErrorConstants("E500", "服务异常");
}
