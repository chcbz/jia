package cn.jia.core.ldap;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.io.Serializable;

/**
 * 分页查询返回实体
 * @author chcbz
 * @date 2016年8月25日 下午4:57:43
 * @param <T>
 */
@JsonInclude(value=Include.NON_NULL)
public class LdapRequestPage<T> implements Serializable{
    private static final long serialVersionUID = -7995644969484016692L;
    /** 请求的序列，用来跟客户端保持同步 */
    private Integer draw;
    /** 页码 */
    private String nextTag;
    /** 每页长度 */
    private Integer pageSize = Integer.MAX_VALUE;

    private Long total;

    private T search;

    private String extra_search;

    public T getSearch() {
        return search;
    }

    public void setSearch(T search) {
        this.search = search;
    }

    public Integer getDraw() {
        return draw;
    }

    public void setDraw(Integer draw) {
        this.draw = draw;
    }

    public LdapRequestPage() {
        super();
    }

    public String getNextTag() {
        return nextTag;
    }

    public void setNextTag(String nextTag) {
        this.nextTag = nextTag;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public Long getTotal() {
        return total;
    }

    public void setTotal(Long total) {
        this.total = total;
    }

    public String getExtra_search() {
        return extra_search;
    }

    public void setExtra_search(String extra_search) {
        this.extra_search = extra_search;
    }

    @Override
    public String toString() {
        return "JSONReqPage [draw=" + draw + ", nextTag=" + nextTag + ", pageSize=" + pageSize + ", search="
                + search + ", extra_search=" + extra_search + "]";
    }

}