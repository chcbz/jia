package cn.jia.isp.service.impl;

import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.util.DateUtil;
import cn.jia.isp.dao.CarBrandAudiMapper;
import cn.jia.isp.dao.CarBrandMapper;
import cn.jia.isp.dao.CarBrandMfMapper;
import cn.jia.isp.dao.CarBrandVersionMapper;
import cn.jia.isp.entity.CarBrand;
import cn.jia.isp.entity.CarBrandAudi;
import cn.jia.isp.entity.CarBrandMf;
import cn.jia.isp.entity.CarBrandVersion;
import cn.jia.isp.service.CarService;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class CarServiceImpl implements CarService {
	
	@Autowired
	private CarBrandMapper carBrandMapper;
	@Autowired
	private CarBrandAudiMapper carBrandAudiMapper;
	@Autowired
	private CarBrandMfMapper carBrandMfMapper;
	@Autowired
	private CarBrandVersionMapper carBrandVersionMapper;
	
	@Override
	public Page<CarBrand> listBrand(CarBrand example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return carBrandMapper.selectByExample(example);
	}
	@Override
	public CarBrand createBrand(CarBrand record) {
		long now = DateUtil.genTime(new Date());
		record.setClientId(EsSecurityHandler.clientId());
		record.setAddTime(now);
		record.setUpTime(now);
		carBrandMapper.insertSelective(record);
		return record;
	}
	@Override
	public CarBrand findBrand(Integer id) {
		return carBrandMapper.selectByPrimaryKey(id);
	}
	@Override
	public CarBrand updateBrand(CarBrand record) {

		long now = DateUtil.genTime(new Date());
		record.setUpTime(now);
		carBrandMapper.updateByPrimaryKeySelective(record);
		return record;
	}
	@Override
	public void deleteBrand(Integer id) {
		carBrandMapper.deleteByPrimaryKey(id);
	}
	
	@Override
	public Page<CarBrandAudi> listBrandAudi(CarBrandAudi example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return carBrandAudiMapper.selectByExample(example);
	}
	@Override
	public CarBrandAudi createBrandAudi(CarBrandAudi record) {
		record.setClientId(EsSecurityHandler.clientId());
		carBrandAudiMapper.insertSelective(record);
		return record;
	}
	@Override
	public CarBrandAudi findBrandAudi(Integer id) throws Exception {
		return carBrandAudiMapper.selectByPrimaryKey(id);
	}
	@Override
	public CarBrandAudi updateBrandAudi(CarBrandAudi record) {
		carBrandAudiMapper.updateByPrimaryKeySelective(record);
		return record;
	}
	@Override
	public void deleteBrandAudi(Integer id) {
		carBrandAudiMapper.deleteByPrimaryKey(id);
	}
	
	@Override
	public Page<CarBrandMf> listBrandMf(CarBrandMf example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return carBrandMfMapper.selectByExample(example);
	}
	@Override
	public CarBrandMf createBrandMf(CarBrandMf record) {
		record.setClientId(EsSecurityHandler.clientId());
		carBrandMfMapper.insertSelective(record);
		return record;
	}
	@Override
	public CarBrandMf findBrandMf(Integer id) throws Exception {
		return carBrandMfMapper.selectByPrimaryKey(id);
	}
	@Override
	public CarBrandMf updateBrandMf(CarBrandMf record) {
		carBrandMfMapper.updateByPrimaryKeySelective(record);
		return record;
	}
	@Override
	public void deleteBrandMf(Integer id) {
		carBrandMfMapper.deleteByPrimaryKey(id);
	}
	
	@Override
	public Page<CarBrandVersion> listBrandVersion(CarBrandVersion example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return carBrandVersionMapper.selectByExample(example);
	}
	@Override
	public CarBrandVersion createBrandVersion(CarBrandVersion record) {
		record.setClientId(EsSecurityHandler.clientId());
		carBrandVersionMapper.insertSelective(record);
		return record;
	}
	@Override
	public CarBrandVersion findBrandVersion(Integer id) throws Exception {
		return carBrandVersionMapper.selectByPrimaryKey(id);
	}
	@Override
	public CarBrandVersion updateBrandVersion(CarBrandVersion record) {
		carBrandVersionMapper.updateByPrimaryKeySelective(record);
		return record;
	}
	@Override
	public void deleteBrandVersion(Integer id) {
		carBrandVersionMapper.deleteByPrimaryKey(id);
	}
}
