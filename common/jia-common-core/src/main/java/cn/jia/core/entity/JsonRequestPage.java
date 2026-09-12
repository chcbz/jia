package cn.jia.core.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 分页查询返回实体
 * @author chcbz
 * @since 2016年8月25日 下午4:57:43
 * @param <T>
 */
@Setter
@Getter
@ToString(callSuper=true)
@JsonInclude(value=Include.NON_NULL)
public class JsonRequestPage<T>{
    public static final int DEFAULT_PAGE_SIZE = 100;
    public static final int MAX_PAGE_SIZE = 100;
	/** 请求的序列，用来跟客户端保持同步 */
    private Integer draw;
    /** 页码 */
    private Integer pageNum = 1;
    /** 每页长度 */
    private Integer pageSize = DEFAULT_PAGE_SIZE;
    
    private T search;
	@JsonProperty("extra_search")
    private String extraSearch;

    /** 排序字段 */
    private String orderBy;

    /**
     * Keeps every JSON pagination endpoint on the same bounded contract. Oversized
     * requests are clamped for wire compatibility instead of introducing a new
     * validation error response.
     */
    public void setPageSize(Integer pageSize) {
        if (pageSize == null) {
            this.pageSize = DEFAULT_PAGE_SIZE;
        } else if (pageSize < 1) {
            this.pageSize = 1;
        } else {
            this.pageSize = Math.min(pageSize, MAX_PAGE_SIZE);
        }
    }

}
