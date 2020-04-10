package cn.jia.isp.service;

import com.github.pagehelper.Page;

import cn.jia.isp.entity.CarBrand;
import cn.jia.isp.entity.CarBrandAudi;
import cn.jia.isp.entity.CarBrandMf;
import cn.jia.isp.entity.CarBrandVersion;

public interface CarService {
	
	Page<CarBrand> listBrand(CarBrand example, int pageNo, int pageSize);
	
	CarBrand createBrand(CarBrand record);

	CarBrand findBrand(Integer id) throws Exception;

	CarBrand updateBrand(CarBrand record);

	void deleteBrand(Integer id);
	
	Page<CarBrandAudi> listBrandAudi(CarBrandAudi example, int pageNo, int pageSize);
	
	CarBrandAudi createBrandAudi(CarBrandAudi record);

	CarBrandAudi findBrandAudi(Integer id) throws Exception;

	CarBrandAudi updateBrandAudi(CarBrandAudi record);

	void deleteBrandAudi(Integer id);
	
	Page<CarBrandMf> listBrandMf(CarBrandMf example, int pageNo, int pageSize);
	
	CarBrandMf createBrandMf(CarBrandMf record);

	CarBrandMf findBrandMf(Integer id) throws Exception;

	CarBrandMf updateBrandMf(CarBrandMf record);

	void deleteBrandMf(Integer id);
	
	Page<CarBrandVersion> listBrandVersion(CarBrandVersion example, int pageNo, int pageSize);
	
	CarBrandVersion createBrandVersion(CarBrandVersion record);

	CarBrandVersion findBrandVersion(Integer id) throws Exception;

	CarBrandVersion updateBrandVersion(CarBrandVersion record);

	void deleteBrandVersion(Integer id);
}
