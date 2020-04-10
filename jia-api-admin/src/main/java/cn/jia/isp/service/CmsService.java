package cn.jia.isp.service;

import cn.jia.isp.entity.*;
import com.github.pagehelper.Page;

import java.util.Map;

public interface CmsService {
	
	Page<CmsTable> listTable(CmsTable example, int pageNo, int pageSize);
	
	CmsTable createTable(CmsTableDTO record);

	CmsTable findTable(Integer id);

	CmsTable updateTable(CmsTable record);

	void deleteTable(Integer id, String clientId);
	
	Page<CmsColumn> listColumn(CmsColumn example, int pageNo, int pageSize);
	
	CmsColumn createColumn(CmsColumnDTO record, String clientId);

	CmsColumn findColumn(Integer id);

	CmsColumn updateColumn(CmsColumnDTO record, String clientId);

	void deleteColumn(Integer id, String clientId);
	
	CmsConfig selectConfig(String clientId);
	
	void createConfig(CmsConfig config);
	
	void updateConfig(CmsConfig config);
	
	Page<Map<String, Object>> listRow(CmsRowExample example, int pageNo, int pageSize);
	
	int createRow(CmsRowDTO record);

	Map<String, Object> findRow(CmsRowDTO record);

	int updateRow(CmsRowDTO record);

	void deleteRow(CmsRowDTO record);
	
}
