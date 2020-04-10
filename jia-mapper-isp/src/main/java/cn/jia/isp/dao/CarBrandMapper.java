package cn.jia.isp.dao;

import com.github.pagehelper.Page;

import cn.jia.isp.entity.CarBrand;

public interface CarBrandMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(CarBrand record);

    int insertSelective(CarBrand record);

    CarBrand selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(CarBrand record);

    int updateByPrimaryKey(CarBrand record);
    
    Page<CarBrand> selectByExample(CarBrand example);
}