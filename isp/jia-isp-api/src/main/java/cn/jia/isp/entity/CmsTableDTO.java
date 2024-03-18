package cn.jia.isp.entity;

import java.util.List;

public class CmsTableDTO extends CmsTableEntity {

    private List<CmsRowEntity> rows;
    
    private List<CmsColumnDTO> columns;

	public List<CmsRowEntity> getRows() {
		return rows;
	}

	public void setRows(List<CmsRowEntity> rows) {
		this.rows = rows;
	}

	public List<CmsColumnDTO> getColumns() {
		return columns;
	}

	public void setColumns(List<CmsColumnDTO> columns) {
		this.columns = columns;
	}
}