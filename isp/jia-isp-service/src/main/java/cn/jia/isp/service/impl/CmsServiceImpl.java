package cn.jia.isp.service.impl;

import cn.jia.core.common.EsSecurityHandler;
import cn.jia.isp.dao.CmsColumnDao;
import cn.jia.isp.dao.CmsConfigDao;
import cn.jia.isp.dao.CmsRowDao;
import cn.jia.isp.dao.CmsTableDao;
import cn.jia.isp.entity.*;
import cn.jia.isp.service.CmsService;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Named
public class CmsServiceImpl implements CmsService {
	
	@Inject
	private CmsTableDao cmsTableDao;
	@Inject
	private CmsColumnDao cmsColumnDao;
	@Inject
	private CmsConfigDao cmsConfigDao;
	@Inject
	private CmsRowDao cmsRowDao;
	
	@Override
	public PageInfo<CmsTableEntity> listTable(CmsTableEntity example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return PageInfo.of(cmsTableDao.selectByEntity(example));
	}
	@Override
	@Transactional
	public CmsTableEntity createTable(CmsTableDTO record) {
		String clientId = EsSecurityHandler.clientId();
		record.setClientId(clientId);
		cmsTableDao.insert(record);
		for(CmsColumnEntity column : record.getColumns()) {
			column.setTableId(record.getId());
			cmsColumnDao.insert(column);
		}
		CmsConfigEntity config = cmsConfigDao.selectById(clientId);
		record.setName("cms_" + config.getTablePrefix() + "_" + record.getName());
		cmsRowDao.createTable(record);
		return record;
	}
	@Override
	public CmsTableEntity findTable(Long id) {
		return cmsTableDao.selectById(id);
	}
	@Override
	public CmsTableEntity findTableByName(String name) {
		return cmsTableDao.findByName(name);
	}
	@Override
	public CmsTableEntity updateTable(CmsTableEntity record) {
		cmsTableDao.updateById(record);
		return record;
	}
	@Override
	@Transactional
	public void deleteTable(Long id) {
		CmsTableEntity record = cmsTableDao.selectById(id);
		CmsConfigEntity config = cmsConfigDao.selectById(EsSecurityHandler.clientId());
		record.setName("cms_" + config.getTablePrefix() + "_" + record.getName());
		cmsRowDao.dropTable(record);
		cmsTableDao.deleteById(id);
	}
	
	@Override
	public PageInfo<CmsColumnEntity> listColumn(CmsColumnEntity example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return PageInfo.of(cmsColumnDao.selectByEntity(example));
	}
	@Override
	public CmsColumnEntity createColumn(CmsColumnEntity record) {
		cmsColumnDao.insert(record);
		return record;
	}
	@Override
	public CmsColumnEntity findColumn(Long id) {
		return cmsColumnDao.selectById(id);
	}
	@Override
	public CmsColumnEntity updateColumn(CmsColumnEntity record) {
		cmsColumnDao.updateById(record);
		return record;
	}
	@Override
	public void deleteColumn(Long id) {
		cmsColumnDao.deleteById(id);
	}
	
	@Override
	public CmsConfigEntity selectConfig(String clientId) {
		return cmsConfigDao.selectById(clientId);
	}

	@Override
	public void createConfig(CmsConfigEntity config) {
		cmsConfigDao.insert(config);
	}

	@Override
	public void updateConfig(CmsConfigEntity config) {
		cmsConfigDao.updateById(config);
	}
	
	@Override
	public PageInfo<Map<String, Object>> listRow(CmsRowExample example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return PageInfo.of(cmsRowDao.selectByExample(example));
	}
	@Override
	public int createRow(CmsRowDTO record) {
		return cmsRowDao.insert(record);
	}
	@Override
	public Map<String, Object> findRow(CmsRowDTO record) {
		return cmsRowDao.selectById(record);
	}
	@Override
	public int updateRow(CmsRowDTO record) {
		return cmsRowDao.updateById(record);
	}
	@Override
	public void deleteRow(CmsRowDTO record) {
		cmsRowDao.deleteById(record);
	}
}
