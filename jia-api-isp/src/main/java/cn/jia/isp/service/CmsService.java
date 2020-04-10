package cn.jia.isp.service;

import cn.jia.isp.entity.*;
import com.github.pagehelper.Page;

import java.util.Map;

public interface CmsService {
	
	Page<CmsTable> listTable(CmsTable example, int pageNo, int pageSize);
	
	CmsTable createTable(CmsTableDTO record);

	CmsTable findTable(Integer id) throws Exception;

	CmsTable findTableByName(String name);

	CmsTable updateTable(CmsTable record);

	void deleteTable(Integer id);
	
	Page<CmsColumn> listColumn(CmsColumn example, int pageNo, int pageSize);
	
	CmsColumn createColumn(CmsColumn record);

	CmsColumn findColumn(Integer id) throws Exception;

	CmsColumn updateColumn(CmsColumn record);

	void deleteColumn(Integer id);
	
	CmsConfig selectConfig(String clientId);
	
	void createConfig(CmsConfig config);
	
	void updateConfig(CmsConfig config);
	
	Page<Map<String, Object>> listRow(CmsRowExample example, int pageNo, int pageSize);
	
	int createRow(CmsRowDTO record);

	Map<String, Object> findRow(CmsRowDTO record);

	int updateRow(CmsRowDTO record);

	void deleteRow(CmsRowDTO record);
	
}
