package cn.jia.isp.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.isp.dao.CarBrandAudiDao;
import cn.jia.isp.entity.CarBrandAudiEntity;
import cn.jia.isp.mapper.CarBrandAudiMapper;
import jakarta.inject.Named;

import java.util.List;

@Named
public class CarBrandAudiDaoImpl extends BaseDaoImpl<CarBrandAudiMapper, CarBrandAudiEntity> implements CarBrandAudiDao {

    @Override
    public List<CarBrandAudiEntity> selectByEntity(CarBrandAudiEntity example) {
        return baseMapper.selectByExample(example);
    }
}