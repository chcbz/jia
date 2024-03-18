package cn.jia.isp.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.isp.dao.CarBrandMfDao;
import cn.jia.isp.entity.CarBrandMfEntity;
import cn.jia.isp.mapper.CarBrandMfMapper;
import jakarta.inject.Named;

import java.util.List;

@Named
public class CarBrandMfDaoImpl extends BaseDaoImpl<CarBrandMfMapper, CarBrandMfEntity> implements CarBrandMfDao {

    @Override
    public List<CarBrandMfEntity> selectByEntity(CarBrandMfEntity example) {
        return baseMapper.selectByExample(example);
    }
}