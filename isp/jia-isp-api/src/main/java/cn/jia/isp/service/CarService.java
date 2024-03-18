package cn.jia.isp.service;

import cn.jia.isp.entity.CarBrandEntity;
import cn.jia.isp.entity.CarBrandAudiEntity;
import cn.jia.isp.entity.CarBrandMfEntity;
import cn.jia.isp.entity.CarBrandVersionEntity;
import com.github.pagehelper.PageInfo;

public interface CarService {
	
	PageInfo<CarBrandEntity> listBrand(CarBrandEntity example, int pageNo, int pageSize);
	
	CarBrandEntity createBrand(CarBrandEntity record);

	CarBrandEntity findBrand(Long id) throws Exception;

	CarBrandEntity updateBrand(CarBrandEntity record);

	void deleteBrand(Long id);
	
	PageInfo<CarBrandAudiEntity> listBrandAudi(CarBrandAudiEntity example, int pageNo, int pageSize);
	
	CarBrandAudiEntity createBrandAudi(CarBrandAudiEntity record);

	CarBrandAudiEntity findBrandAudi(Long id) throws Exception;

	CarBrandAudiEntity updateBrandAudi(CarBrandAudiEntity record);

	void deleteBrandAudi(Long id);
	
	PageInfo<CarBrandMfEntity> listBrandMf(CarBrandMfEntity example, int pageNo, int pageSize);
	
	CarBrandMfEntity createBrandMf(CarBrandMfEntity record);

	CarBrandMfEntity findBrandMf(Long id) throws Exception;

	CarBrandMfEntity updateBrandMf(CarBrandMfEntity record);

	void deleteBrandMf(Long id);
	
	PageInfo<CarBrandVersionEntity> listBrandVersion(CarBrandVersionEntity example, int pageNo, int pageSize);
	
	CarBrandVersionEntity createBrandVersion(CarBrandVersionEntity record);

	CarBrandVersionEntity findBrandVersion(Long id) throws Exception;

	CarBrandVersionEntity updateBrandVersion(CarBrandVersionEntity record);

	void deleteBrandVersion(Long id);
}
