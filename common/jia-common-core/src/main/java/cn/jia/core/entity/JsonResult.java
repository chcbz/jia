package cn.jia.core.entity;

import cn.jia.core.common.EsHandler;
import cn.jia.core.exception.EsErrorConstants;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;


/**
 * JsonResult : Response JsonResult for RESTful,封装返回JSON格式的数据
 *
 * @author StarZou
 * @since 2014年5月26日 上午10:51:46
 */
@Setter
@Getter
@JsonInclude(value = Include.NON_NULL)
public class JsonResult<T> extends Result {

	@Serial
	private static final long serialVersionUID = 7880907731807860636L;

    /** Version and request correlation are HTTP boundary metadata. */
    @Setter(lombok.AccessLevel.NONE)
    private final int protocolVersion = 1;

    @JsonInclude(Include.ALWAYS)
    private String requestId;

    @JsonInclude(Include.ALWAYS)
    private T data;

    public JsonResult() {
		super();
	}

	/**
	 * 自定义返回的结果
	 *
	 * @param data 数据
	 * @param message 消息
	 */
	public JsonResult(T data, String message, String code) {
		this.data = data;
		super.setMsg(message);
		super.setCode(code);
	}


	/**
	 * 成功返回数据和消息
	 *
	 * @param data 数据
	 * @param message 消息
	 */
	public JsonResult(T data, String message) {
		this.data = data;
		super.setMsg(message);
		super.setCode(EsErrorConstants.SUCCESS.getCode());
	}

	/**
	 * 成功返回数据
	 *
	 * @param data 数据
	 */
	public JsonResult(T data) {
		this.data = data;
		super.setMsg("ok");
		super.setCode(EsErrorConstants.SUCCESS.getCode());
	}

	/**
	 * 返回错误消息
	 *
	 * @param errorConstants 错误信息
	 * @return 消息体
	 */
	public static <T> JsonResult<T> failure(EsErrorConstants errorConstants) {
		JsonResult<T> result = new JsonResult<>();
		result.setMsg(errorConstants.getMessage());
		result.setCode(errorConstants.getCode());
		return result;
	}

	/**
	 * 返回错误消息
	 * 
	 * @param msg 消息key
	 * @return 消息体
	 */
	public static <T> JsonResult<T> failure(String code, String msg) {
		JsonResult<T> result = new JsonResult<>();
		result.setMsg(msg);
		result.setCode(code);
		return result;
	}
	
	public static <T> JsonResult<T> failure(HttpServletRequest request, String code) {
		JsonResult<T> result = new JsonResult<>();
		result.setMsg(EsHandler.getMessage(request, code));
		result.setCode(code);
		return result;
	}

	/**
	 * 返回成功消息
	 * @return 消息体
	 */
	public static <T> JsonResult<T> success() {
		JsonResult<T> result = new JsonResult<>();
		result.setMsg("ok");
		result.setCode(EsErrorConstants.SUCCESS.getCode());
		return result;
	}

	public static <T> JsonResult<T> success(HttpServletRequest request, String msg) {
		JsonResult<T> result = new JsonResult<>();
		result.setMsg(EsHandler.getMessage(request, msg));
		result.setCode(EsErrorConstants.SUCCESS.getCode());
		return result;
	}

	public static <T> JsonResult<T> success(T data) {
		JsonResult<T> result = new JsonResult<>();
		result.setMsg("ok");
		result.setCode(EsErrorConstants.SUCCESS.getCode());
		result.setData(data);
		return result;
	}

	@Override
	public String toString() {
		return "JsonResult {protocolVersion=" + protocolVersion + ", requestId=" + requestId + "}";
	}
}