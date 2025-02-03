package cn.jia.core.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;

/**
 * 分页查询返回实体
 * @author chcbz
 * @since 2016年8月25日 下午4:57:43
 * @param <T>
 */
@Setter
@Getter
@JsonInclude(value=Include.NON_NULL)
public class JsonRequestPage<T> implements Serializable {
	@Serial
	private static final long serialVersionUID = -7995644969484016692L;
	/** 请求的序列，用来跟客户端保持同步 */
    private Integer draw;
    /** 页码 */
    private Integer pageNum = 1;
    /** 每页长度 */
    private Integer pageSize = Integer.MAX_VALUE;
    
    private T search;
	@JsonProperty("extra_search")
    private String extraSearch;

    public JsonRequestPage() {
        super();
    }

    @Override
	public String toString() {
		return "JsonRequestPage [draw=" + draw + ", pageNum=" + pageNum + ", pageSize=" + pageSize + ", search="
				+ search + ", extra_search=" + extraSearch + "]";
	}

}