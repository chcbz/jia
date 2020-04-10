package cn.jia.isp.dao;

import cn.jia.isp.entity.CarBrandAudi;
import com.github.pagehelper.Page;

public interface CarBrandAudiMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(CarBrandAudi record);

    int insertSelective(CarBrandAudi record);

    CarBrandAudi selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(CarBrandAudi record);

    int updateByPrimaryKey(CarBrandAudi record);
    
    Page<CarBrandAudi> selectByExample(CarBrandAudi example);
}