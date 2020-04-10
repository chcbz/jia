package cn.jia.core.ldap;

import cn.jia.core.common.EsHandler;
import cn.jia.core.entity.JSONResult;
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
public class LdapResultPage<T> extends JSONResult<T> {
    private static final long serialVersionUID = 7880907731807860636L;

    /** 请求的序列，用来跟客户端保持同步 */
    private String nextTag;
    /** 总记录数 */
    private Long total;

	public String getNextTag() {
		return nextTag;
	}

	public void setNextTag(String nextTag) {
		this.nextTag = nextTag;
	}

	public Long getTotal() {
		return total;
	}

	public void setTotal(Long total) {
		this.total = total;
	}

	public LdapResultPage() {
        super();
    }

    /**
     * 自定义返回的结果
     *
     * @param data
     * @param message
     */
    public LdapResultPage(T data, String message, String code) {
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
    public LdapResultPage(T data, String message) {
    	super.setData(data);
        super.setMsg(message);
        super.setCode(EsErrorConstants.SUCCESS);
    }

    /**
     * 成功返回数据
     *
     * @param data
     */
    public LdapResultPage(T data) {
    	super.setData(data);
    	super.setMsg("ok");
        super.setCode(EsErrorConstants.SUCCESS);
    }

    /**
     * 返回错误消息
     * @param msg 消息key
     * @return
     */
    public static<T> LdapResultPage<T> failure(HttpServletRequest request, String msg, String code){
    	LdapResultPage<T> result = new LdapResultPage<>();
    	result.setMsg(EsHandler.getMessage(request, msg));
    	result.setCode(code);
    	return result;
    }

	@Override
	public String toString() {
		return "JSONResultPage {nextTag=" + nextTag + ", total=" + total + "}";
	}
    
}