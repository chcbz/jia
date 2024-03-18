package cn.jia.isp.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.isp.dao.IspDnsRecordDao;
import cn.jia.isp.entity.IspDnsRecordEntity;
import cn.jia.isp.mapper.IspDnsRecordMapper;
import jakarta.inject.Named;

import java.util.List;

@Named
public class IspDnsRecordDaoImpl extends BaseDaoImpl<IspDnsRecordMapper, IspDnsRecordEntity> implements IspDnsRecordDao {
    @Override
    public IspDnsRecordEntity selectByDomain(String domain) {
        return baseMapper.selectByDomain(domain);
    }

    @Override
    public List<IspDnsRecordEntity> selectByZoneId(Long zoneId) {
        return baseMapper.selectByZoneId(zoneId);
    }
}