package cn.jia.isp.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.isp.dao.CarBrandDao;
import cn.jia.isp.entity.CarBrandEntity;
import cn.jia.isp.mapper.CarBrandMapper;
import jakarta.inject.Named;

import java.util.List;

@Named
public class CarBrandDaoImpl extends BaseDaoImpl<CarBrandMapper, CarBrandEntity> implements CarBrandDao {

    @Override
    public List<CarBrandEntity> selectByEntity(CarBrandEntity example) {
        return baseMapper.selectByExample(example);
    }
}