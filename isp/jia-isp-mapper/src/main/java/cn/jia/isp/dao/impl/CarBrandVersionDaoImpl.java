package cn.jia.isp.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.isp.dao.CarBrandVersionDao;
import cn.jia.isp.entity.CarBrandVersionEntity;
import cn.jia.isp.mapper.CarBrandVersionMapper;
import jakarta.inject.Named;

import java.util.List;

@Named
public class CarBrandVersionDaoImpl extends BaseDaoImpl<CarBrandVersionMapper, CarBrandVersionEntity>
        implements CarBrandVersionDao {

    @Override
    public List<CarBrandVersionEntity> selectByEntity(CarBrandVersionEntity example) {
        return baseMapper.selectByExample(example);
    }
}