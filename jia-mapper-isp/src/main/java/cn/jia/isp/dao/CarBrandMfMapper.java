package cn.jia.isp.dao;

import com.github.pagehelper.Page;

import cn.jia.isp.entity.CarBrandMf;

public interface CarBrandMfMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(CarBrandMf record);

    int insertSelective(CarBrandMf record);

    CarBrandMf selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(CarBrandMf record);

    int updateByPrimaryKey(CarBrandMf record);
    
    Page<CarBrandMf> selectByExample(CarBrandMf example);
}