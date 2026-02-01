package cn.jia.isp.service.impl;

import cn.jia.core.context.EsContextHolder;
import cn.jia.core.util.DateUtil;
import cn.jia.isp.dao.CarBrandAudiDao;
import cn.jia.isp.dao.CarBrandDao;
import cn.jia.isp.dao.CarBrandMfDao;
import cn.jia.isp.dao.CarBrandVersionDao;
import cn.jia.isp.entity.CarBrandAudiEntity;
import cn.jia.isp.entity.CarBrandEntity;
import cn.jia.isp.entity.CarBrandMfEntity;
import cn.jia.isp.entity.CarBrandVersionEntity;
import cn.jia.isp.service.CarService;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import jakarta.inject.Inject;
import jakarta.inject.Named;

@Named
public class CarServiceImpl implements CarService {
	
	@Inject
	private CarBrandDao carBrandDao;
	@Inject
	private CarBrandAudiDao carBrandAudiDao;
	@Inject
	private CarBrandMfDao carBrandMfDao;
	@Inject
	private CarBrandVersionDao carBrandVersionDao;
	
	@Override
	public PageInfo<CarBrandEntity> listBrand(CarBrandEntity example, int pageNum, int pageSize, String orderBy) {
		PageHelper.startPage(pageNum, pageSize, orderBy);
		return PageInfo.of(carBrandDao.selectByEntity(example));
	}
	@Override
	public CarBrandEntity createBrand(CarBrandEntity record) {
		long now = DateUtil.nowTime();
		record.setClientId(EsContextHolder.getContext().getClientId());
		record.setAddTime(now);
		record.setUpTime(now);
		carBrandDao.insert(record);
		return record;
	}
	@Override
	public CarBrandEntity findBrand(Long id) {
		return carBrandDao.selectById(id);
	}
	@Override
	public CarBrandEntity updateBrand(CarBrandEntity record) {

		long now = DateUtil.nowTime();
		record.setUpTime(now);
		carBrandDao.updateById(record);
		return record;
	}
	@Override
	public void deleteBrand(Long id) {
		carBrandDao.deleteById(id);
	}
	
	@Override
	public PageInfo<CarBrandAudiEntity> listBrandAudi(CarBrandAudiEntity example, int pageNum, int pageSize, String orderBy) {
		PageHelper.startPage(pageNum, pageSize, orderBy);
		return PageInfo.of(carBrandAudiDao.selectByEntity(example));
	}
	@Override
	public CarBrandAudiEntity createBrandAudi(CarBrandAudiEntity record) {
		record.setClientId(EsContextHolder.getContext().getClientId());
		carBrandAudiDao.insert(record);
		return record;
	}
	@Override
	public CarBrandAudiEntity findBrandAudi(Long id) throws Exception {
		return carBrandAudiDao.selectById(id);
	}
	@Override
	public CarBrandAudiEntity updateBrandAudi(CarBrandAudiEntity record) {
		carBrandAudiDao.updateById(record);
		return record;
	}
	@Override
	public void deleteBrandAudi(Long id) {
		carBrandAudiDao.deleteById(id);
	}
	
	@Override
	public PageInfo<CarBrandMfEntity> listBrandMf(CarBrandMfEntity example, int pageNum, int pageSize, String orderBy) {
		PageHelper.startPage(pageNum, pageSize, orderBy);
		return PageInfo.of(carBrandMfDao.selectByEntity(example));
	}
	@Override
	public CarBrandMfEntity createBrandMf(CarBrandMfEntity record) {
		record.setClientId(EsContextHolder.getContext().getClientId());
		carBrandMfDao.insert(record);
		return record;
	}
	@Override
	public CarBrandMfEntity findBrandMf(Long id) throws Exception {
		return carBrandMfDao.selectById(id);
	}
	@Override
	public CarBrandMfEntity updateBrandMf(CarBrandMfEntity record) {
		carBrandMfDao.updateById(record);
		return record;
	}
	@Override
	public void deleteBrandMf(Long id) {
		carBrandMfDao.deleteById(id);
	}
	
	@Override
	public PageInfo<CarBrandVersionEntity> listBrandVersion(CarBrandVersionEntity example, int pageNum, int pageSize, String orderBy) {
		PageHelper.startPage(pageNum, pageSize, orderBy);
		return PageInfo.of(carBrandVersionDao.selectByEntity(example));
	}
	@Override
	public CarBrandVersionEntity createBrandVersion(CarBrandVersionEntity record) {
		record.setClientId(EsContextHolder.getContext().getClientId());
		carBrandVersionDao.insert(record);
		return record;
	}
	@Override
	public CarBrandVersionEntity findBrandVersion(Long id) throws Exception {
		return carBrandVersionDao.selectById(id);
	}
	@Override
	public CarBrandVersionEntity updateBrandVersion(CarBrandVersionEntity record) {
		carBrandVersionDao.updateById(record);
		return record;
	}
	@Override
	public void deleteBrandVersion(Long id) {
		carBrandVersionDao.deleteById(id);
	}
}
