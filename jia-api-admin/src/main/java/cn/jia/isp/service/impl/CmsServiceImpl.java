package cn.jia.isp.service.impl;

import cn.jia.isp.dao.CmsColumnMapper;
import cn.jia.isp.dao.CmsConfigMapper;
import cn.jia.isp.dao.CmsRowMapper;
import cn.jia.isp.dao.CmsTableMapper;
import cn.jia.isp.entity.*;
import cn.jia.isp.service.CmsService;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class CmsServiceImpl implements CmsService {
	
	@Autowired
	private CmsTableMapper cmsTableMapper;
	@Autowired
	private CmsColumnMapper cmsColumnMapper;
	@Autowired
	private CmsConfigMapper cmsConfigMapper;
	@Autowired
	private CmsRowMapper cmsRowMapper;
	
	@Override
	public Page<CmsTable> listTable(CmsTable example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return cmsTableMapper.selectByExample(example);
	}
	@Override
	@Transactional
	public CmsTable createTable(CmsTableDTO record) {
		cmsTableMapper.insertSelective(record);
		for(CmsColumn column : record.getColumns()) {
			column.setTableId(record.getId());
			cmsColumnMapper.insertSelective(column);
		}
		CmsConfig config = cmsConfigMapper.selectByPrimaryKey(record.getClientId());
		record.setName("cms_" + config.getTablePrefix() + "_" + record.getName());
		cmsRowMapper.createTable(record);
		return record;
	}

	@Override
	public CmsTable findTable(Integer id) {
		return cmsTableMapper.selectByPrimaryKey(id);
	}

	@Override
	public CmsTable updateTable(CmsTable record) {
		cmsTableMapper.updateByPrimaryKeySelective(record);
		return record;
	}
	@Override
	@Transactional
	public void deleteTable(Integer id, String clientId) {
		CmsTable record = cmsTableMapper.selectByPrimaryKey(id);
		CmsConfig config = cmsConfigMapper.selectByPrimaryKey(clientId);
		record.setName("cms_" + config.getTablePrefix() + "_" + record.getName());
		cmsRowMapper.dropTable(record);
		cmsTableMapper.deleteByPrimaryKey(id);
	}
	
	@Override
	public Page<CmsColumn> listColumn(CmsColumn example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return cmsColumnMapper.selectByExample(example);
	}
	@Override
	public CmsColumn createColumn(CmsColumnDTO record, String clientId) {
		CmsTable table = cmsTableMapper.selectByPrimaryKey(record.getTableId());
		CmsConfig config = cmsConfigMapper.selectByPrimaryKey(clientId);
		record.setTableName("cms_" + config.getTablePrefix() + "_" + table.getName());
		cmsRowMapper.addColumn(record);
		cmsColumnMapper.insertSelective(record);
		return record;
	}
	@Override
	public CmsColumn findColumn(Integer id) {
		return cmsColumnMapper.selectByPrimaryKey(id);
	}
	@Override
	public CmsColumn updateColumn(CmsColumnDTO record, String clientId) {
		CmsColumn column = cmsColumnMapper.selectByPrimaryKey(record.getId());
		record.setName(column.getName()); // 列名不能修改
		CmsTable table = cmsTableMapper.selectByPrimaryKey(record.getTableId());
		CmsConfig config = cmsConfigMapper.selectByPrimaryKey(clientId);
		record.setTableName("cms_" + config.getTablePrefix() + "_" + table.getName());
		cmsRowMapper.modifyColumn(record);
		cmsColumnMapper.updateByPrimaryKeySelective(record);
		return record;
	}
	@Override
	public void deleteColumn(Integer id, String clientId) {
		CmsColumn record = cmsColumnMapper.selectByPrimaryKey(id);
		CmsTable table = cmsTableMapper.selectByPrimaryKey(record.getTableId());
		CmsConfig config = cmsConfigMapper.selectByPrimaryKey(clientId);
		CmsColumnDTO column = new CmsColumnDTO();
		column.setName(record.getName());
		column.setTableName("cms_" + config.getTablePrefix() + "_" + table.getName());
		cmsRowMapper.dropColumn(column);
		cmsColumnMapper.deleteByPrimaryKey(id);
	}
	
	@Override
	public CmsConfig selectConfig(String clientId) {
		return cmsConfigMapper.selectByPrimaryKey(clientId);
	}

	@Override
	public void createConfig(CmsConfig config) {
		cmsConfigMapper.insertSelective(config);
	}

	@Override
	public void updateConfig(CmsConfig config) {
		cmsConfigMapper.updateByPrimaryKeySelective(config);
	}
	
	@Override
	public Page<Map<String, Object>> listRow(CmsRowExample example, int pageNo, int pageSize) {
		CmsConfig config = cmsConfigMapper.selectByPrimaryKey(example.getClientId());
		example.setName("cms_" + config.getTablePrefix() + "_" + example.getName());
		PageHelper.startPage(pageNo, pageSize);
		return cmsRowMapper.selectByExample(example);
	}
	@Override
	public int createRow(CmsRowDTO record) {
		return cmsRowMapper.insertSelective(record);
	}
	@Override
	public Map<String, Object> findRow(CmsRowDTO record) {
		return cmsRowMapper.selectByPrimaryKey(record);
	}
	@Override
	public int updateRow(CmsRowDTO record) {
		return cmsRowMapper.updateByPrimaryKeySelective(record);
	}
	@Override
	public void deleteRow(CmsRowDTO record) {
		cmsRowMapper.deleteByPrimaryKey(record);
	}
}
