package cn.jia.isp.mapper;

import cn.jia.isp.entity.CarBrandVersionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

public interface CarBrandVersionMapper extends BaseMapper<CarBrandVersionEntity> {

    List<CarBrandVersionEntity> selectByExample(CarBrandVersionEntity example);
}