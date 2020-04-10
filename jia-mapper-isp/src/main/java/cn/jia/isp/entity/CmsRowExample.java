package cn.jia.isp.entity;

import java.util.List;

public class CmsRowExample {
    private Integer id;

    private String clientId;

    private String name;

    private String remark;
    
    private List<CmsRow> rows;

    private String orderBy;
    
    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId == null ? null : clientId.trim();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? null : name.trim();
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark == null ? null : remark.trim();
    }

	public List<CmsRow> getRows() {
		return rows;
	}

	public void setRows(List<CmsRow> rows) {
		this.rows = rows;
	}

    public String getOrderBy() {
        return orderBy;
    }

    public void setOrderBy(String orderBy) {
        this.orderBy = orderBy;
    }
}