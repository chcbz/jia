package cn.jia.core.entity;

import cn.jia.core.common.EsHandler;
import cn.jia.core.exception.EsErrorConstants;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import jakarta.servlet.http.HttpServletRequest;


/**
 * JsonResult : Response JsonResult for RESTful,封装返回JSON格式的数据
 *
 * @author StarZou
 * @since 2014年5月26日 上午10:51:46
 */
@JsonInclude(value = Include.NON_NULL)
public class JsonResult<T> extends Result {

	private static final long serialVersionUID = 7880907731807860636L;

	/**
	 * 请求状态
	 */
	private int status = 200;

	private String location;

	/**
	 * 数据
	 */
	private T data;

	public int getStatus() {
		return status;
	}

	public void setStatus(int status) {
		this.status = status;
	}

	public String getLocation() {
		return location;
	}

	public void setLocation(String location) {
		this.location = location;
	}

	public T getData() {
		return data;
	}

	public void setData(T data) {
		// EsHandler.jsonValueToString(data);
		this.data = data;
	}

	public JsonResult() {
		super();
	}

	/**
	 * 自定义返回的结果
	 *
	 * @param data
	 * @param message
	 */
	public JsonResult(T data, String message, String code) {
		this.data = data;
		super.setMsg(message);
		super.setCode(code);
	}

	public JsonResult(T data, String message, String code, int status) {
		this.data = data;
		this.status = status;
		super.setMsg(message);
		super.setCode(code);
	}

	/**
	 * 成功返回数据和消息
	 *
	 * @param data
	 * @param message
	 */
	public JsonResult(T data, String message) {
		this.data = data;
		super.setMsg(message);
		super.setCode(EsErrorConstants.SUCCESS);
	}

	/**
	 * 成功返回数据
	 *
	 * @param data
	 */
	public JsonResult(T data) {
		this.data = data;
		super.setMsg("ok");
		super.setCode(EsErrorConstants.SUCCESS);
	}

	/**
	 * 返回错误消息
	 * 
	 * @param msg 消息key
	 * @return
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
	 * @return
	 */
	public static <T> JsonResult<T> success() {
		JsonResult<T> result = new JsonResult<>();
		result.setMsg("ok");
		result.setCode(EsErrorConstants.SUCCESS);
		return result;
	}

	public static <T> JsonResult<T> success(HttpServletRequest request, String msg) {
		JsonResult<T> result = new JsonResult<>();
		result.setMsg(EsHandler.getMessage(request, msg));
		result.setCode(EsErrorConstants.SUCCESS);
		return result;
	}

	public static <T> JsonResult<T> success(T data) {
		JsonResult<T> result = new JsonResult<>();
		result.setMsg("ok");
		result.setCode(EsErrorConstants.SUCCESS);
		result.setData(data);
		return result;
	}

	@Override
	public String toString() {
		return "JsonResult {status=" + status + ", location=" + location + ", data=" + data + "}";
	}
}