package cn.jia.isp.entity;

import java.util.List;

public class CmsRowDTO {
    private Integer id;

    private String clientId;

    private Integer tableId;

    private String tableName;

    private List<CmsRow> rows;

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

	public List<CmsRow> getRows() {
		return rows;
	}

	public void setRows(List<CmsRow> rows) {
		this.rows = rows;
	}

    public Integer getTableId() {
        return tableId;
    }

    public void setTableId(Integer tableId) {
        this.tableId = tableId;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }
}