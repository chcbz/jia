package cn.jia.isp.dao;

import cn.jia.isp.entity.CarBrandVersion;
import com.github.pagehelper.Page;

public interface CarBrandVersionMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(CarBrandVersion record);

    int insertSelective(CarBrandVersion record);

    CarBrandVersion selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(CarBrandVersion record);

    int updateByPrimaryKey(CarBrandVersion record);
    
    Page<CarBrandVersion> selectByExample(CarBrandVersion example);
}