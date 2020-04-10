package cn.jia.core.entity;

import cn.jia.core.common.EsHandler;
import cn.jia.core.exception.EsErrorConstants;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import javax.servlet.http.HttpServletRequest;



/**
 * 分页查询返回实体
 * @author chcbz
 * @date 2016年8月25日 下午4:57:43
 * @param <T>
 */
@JsonInclude(value=Include.NON_NULL)
public class JSONResultPage<T> extends JSONResult<T> {
    private static final long serialVersionUID = 7880907731807860636L;
    
    /** 请求的序列，用来跟客户端保持同步 */
    private Integer pageNum = 1;
    /** 总记录数 */
    private Long total;

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

	public JSONResultPage() {
        super();
    }

    /**
     * 自定义返回的结果
     *
     * @param data
     * @param message
     */
    public JSONResultPage(T data, String message, String code) {
    	super.setData(data);
        super.setMsg(message);
        super.setCode(code);
    }

    /**
     * 成功返回数据和消息
     *
     * @param data
     * @param message
     */
    public JSONResultPage(T data, String message) {
    	super.setData(data);
        super.setMsg(message);
        super.setCode(EsErrorConstants.SUCCESS);
    }

    /**
     * 成功返回数据
     *
     * @param data
     */
    public JSONResultPage(T data) {
    	super.setData(data);
    	super.setMsg("ok");
        super.setCode(EsErrorConstants.SUCCESS);
    }

    /**
     * 返回错误消息
     * @param msg 消息key
     * @return
     */
    public static<T> JSONResultPage<T> failure(HttpServletRequest request, String msg, String code){
    	JSONResultPage<T> result = new JSONResultPage<>();
    	result.setMsg(EsHandler.getMessage(request, msg));
    	result.setCode(code);
    	return result;
    }

	@Override
	public String toString() {
		return "JSONResultPage {pageNum=" + pageNum + ", total=" + total + "}";
	}
    
}