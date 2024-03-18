package cn.jia.isp.service;

import cn.jia.isp.entity.*;
import com.github.pagehelper.PageInfo;

import java.util.Map;

public interface CmsService {
	
	PageInfo<CmsTableEntity> listTable(CmsTableEntity example, int pageNo, int pageSize);
	
	CmsTableEntity createTable(CmsTableDTO record);

	CmsTableEntity findTable(Long id) throws Exception;

	CmsTableEntity findTableByName(String name);

	CmsTableEntity updateTable(CmsTableEntity record);

	void deleteTable(Long id);
	
	PageInfo<CmsColumnEntity> listColumn(CmsColumnEntity example, int pageNo, int pageSize);
	
	CmsColumnEntity createColumn(CmsColumnEntity record);

	CmsColumnEntity findColumn(Long id) throws Exception;

	CmsColumnEntity updateColumn(CmsColumnEntity record);

	void deleteColumn(Long id);
	
	CmsConfigEntity selectConfig(String clientId);
	
	void createConfig(CmsConfigEntity config);
	
	void updateConfig(CmsConfigEntity config);
	
	PageInfo<Map<String, Object>> listRow(CmsRowExample example, int pageNo, int pageSize);
	
	int createRow(CmsRowDTO record);

	Map<String, Object> findRow(CmsRowDTO record);

	int updateRow(CmsRowDTO record);

	void deleteRow(CmsRowDTO record);
	
}
