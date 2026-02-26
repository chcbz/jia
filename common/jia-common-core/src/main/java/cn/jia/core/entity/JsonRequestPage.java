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
	/** 请求的序列，用来跟客户端保持同步 */
    private Integer draw;
    /** 页码 */
    private Integer pageNum = 1;
    /** 每页长度 */
    private Integer pageSize = Integer.MAX_VALUE;
    
    private T search;
	@JsonProperty("extra_search")
    private String extraSearch;

    /** 排序字段 */
    private String orderBy;

}