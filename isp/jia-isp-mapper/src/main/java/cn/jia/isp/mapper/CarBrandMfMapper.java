package cn.jia.isp.mapper;

import cn.jia.isp.entity.CarBrandMfEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

public interface CarBrandMfMapper extends BaseMapper<CarBrandMfEntity> {

    List<CarBrandMfEntity> selectByExample(CarBrandMfEntity example);
}