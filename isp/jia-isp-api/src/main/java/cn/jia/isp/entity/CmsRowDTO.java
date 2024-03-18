package cn.jia.isp.entity;

import java.util.List;

public class CmsRowDTO {
    private Long id;

    private String clientId;

    private Long tableId;

    private String tableName;

    private List<CmsRowEntity> rows;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId == null ? null : clientId.trim();
    }

	public List<CmsRowEntity> getRows() {
		return rows;
	}

	public void setRows(List<CmsRowEntity> rows) {
		this.rows = rows;
	}

    public Long getTableId() {
        return tableId;
    }

    public void setTableId(Long tableId) {
        this.tableId = tableId;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }
}