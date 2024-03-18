package cn.jia.isp.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.isp.dao.IspDomainDao;
import cn.jia.isp.entity.IspDomainEntity;
import cn.jia.isp.mapper.IspDomainMapper;
import jakarta.inject.Named;

import java.util.List;

@Named
public class IspDomainDaoImpl extends BaseDaoImpl<IspDomainMapper, IspDomainEntity> implements IspDomainDao {
    public List<IspDomainEntity> selectByExample(IspDomainEntity example) {
        return baseMapper.selectByExample(example);
    }
}