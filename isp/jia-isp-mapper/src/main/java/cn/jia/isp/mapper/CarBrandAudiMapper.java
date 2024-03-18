package cn.jia.isp.mapper;

import cn.jia.isp.entity.CarBrandAudiEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

public interface CarBrandAudiMapper extends BaseMapper<CarBrandAudiEntity> {

    List<CarBrandAudiEntity> selectByExample(CarBrandAudiEntity example);
}