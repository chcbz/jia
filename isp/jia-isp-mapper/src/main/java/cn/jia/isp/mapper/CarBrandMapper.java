package cn.jia.isp.mapper;

import cn.jia.isp.entity.CarBrandEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

public interface CarBrandMapper extends BaseMapper<CarBrandEntity> {

    List<CarBrandEntity> selectByExample(CarBrandEntity example);
}