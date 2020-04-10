package cn.jia.isp.entity;

import java.util.List;

public class CmsTableDTO extends CmsTable {

    private List<CmsRow> rows;
    
    private List<CmsColumnDTO> columns;

	public List<CmsRow> getRows() {
		return rows;
	}

	public void setRows(List<CmsRow> rows) {
		this.rows = rows;
	}

	public List<CmsColumnDTO> getColumns() {
		return columns;
	}

	public void setColumns(List<CmsColumnDTO> columns) {
		this.columns = columns;
	}
}