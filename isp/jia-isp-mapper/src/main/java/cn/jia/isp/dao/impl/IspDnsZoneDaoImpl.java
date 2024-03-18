package cn.jia.isp.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.isp.dao.IspDnsZoneDao;
import cn.jia.isp.entity.IspDnsZoneEntity;
import cn.jia.isp.mapper.IspDnsZoneMapper;
import jakarta.inject.Named;

import java.util.List;

@Named
public class IspDnsZoneDaoImpl extends BaseDaoImpl<IspDnsZoneMapper, IspDnsZoneEntity> implements IspDnsZoneDao {
    @Override
    public List<IspDnsZoneEntity> selectByExample(IspDnsZoneEntity record) {
        return baseMapper.selectByExample(record);
    }
}