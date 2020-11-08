package cn.jia.core.entity;

import cn.jia.core.common.EsHandler;
import cn.jia.core.exception.EsErrorConstants;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import javax.servlet.http.HttpServletRequest;
import java.util.List;


/**
 * 分页查询返回实体
 *
 * @author chcbz
 * @date 2016年8月25日 下午4:57:43
 * @param <T>
 */
@JsonInclude(value=Include.NON_NULL)
public class JSONResultPage<T> extends Result {
    private static final long serialVersionUID = 7880907731807860636L;
    
    /** 请求的序列，用来跟客户端保持同步 */
    private Integer pageNum = 1;
    /** 总记录数 */
    private Long total;
    /** http状态 */
	private int status = 200;
	/** 结果内容 */
    private List<T> data;

	public JSONResultPage() {
        super();
    }

    /**
     * 自定义返回的结果
     *
     * @param data 内容
     * @param message 消息
	 * @param code 消息代码
     */
    public JSONResultPage(List<T> data, String message, String code) {
    	this.setData(data);
        super.setMsg(message);
        super.setCode(code);
    }

    /**
     * 成功返回数据和消息
     *
     * @param data 内容
     * @param message 消息
     */
    public JSONResultPage(List<T> data, String message) {
    	this.setData(data);
        super.setMsg(message);
        super.setCode(EsErrorConstants.SUCCESS);
    }

    /**
     * 成功返回数据
     *
     * @param data 内容
     */
    public JSONResultPage(List<T> data) {
    	this.setData(data);
    	super.setMsg("ok");
        super.setCode(EsErrorConstants.SUCCESS);
    }

	/**
	 * 返回错误消息
	 *
	 * @param msg 消息key
	 * @return 错误结果
	 */
	public static <T> JSONResultPage<T> failure(String code, String msg) {
		JSONResultPage<T> result = new JSONResultPage<>();
		result.setMsg(msg);
		result.setCode(code);
		return result;
	}

    /**
     * 返回错误消息
	 *
     * @param msg 消息key
     * @return 错误结果
     */
    public static<T> JSONResultPage<T> failure(HttpServletRequest request, String msg, String code){
    	JSONResultPage<T> result = new JSONResultPage<>();
    	result.setMsg(EsHandler.getMessage(request, msg));
    	result.setCode(code);
    	return result;
    }

	/**
	 * 返回成功消息
	 *
	 * @return 成功结果
	 */
	public static <T> JSONResultPage<T> success() {
		JSONResultPage<T> result = new JSONResultPage<>();
		result.setMsg("ok");
		result.setCode(EsErrorConstants.SUCCESS);
		return result;
	}

	/**
	 * 返回成功结果
	 * @param request http请求
	 * @param msg 消息内容
	 * @param <T> 结果类型
	 * @return 成功结果
	 */
	public static <T> JSONResultPage<T> success(HttpServletRequest request, String msg) {
		JSONResultPage<T> result = new JSONResultPage<>();
		result.setMsg(EsHandler.getMessage(request, msg));
		result.setCode(EsErrorConstants.SUCCESS);
		return result;
	}

	/**
	 * 返回成功结果
	 *
	 * @param data 内容
	 * @param <T> 结果类型
	 * @return 成功结果
	 */
	public static <T> JSONResultPage<T> success(List<T> data) {
		JSONResultPage<T> result = new JSONResultPage<>();
		result.setMsg("ok");
		result.setCode(EsErrorConstants.SUCCESS);
		result.setData(data);
		return result;
	}

	@Override
	public String toString() {
		return "JSONResultPage {pageNum=" + pageNum + ", total=" + total + "}";
	}

	public List<T> getData() {
		return this.data;
	}

	public void setData(List<T> data) {
		this.data = data;
	}

	public int getStatus() {
		return status;
	}

	public void setStatus(int status) {
		this.status = status;
	}

	public Integer getPageNum() {
		return pageNum;
	}

	public void setPageNum(Integer pageNum) {
		this.pageNum = pageNum;
	}

	public Long getTotal() {
		return total;
	}

	public void setTotal(Long total) {
		this.total = total;
	}
}