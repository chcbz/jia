package cn.jia.isp.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.isp.entity.IspDnsRecordEntity;

import java.util.List;

public interface IspDnsRecordDao extends IBaseDao<IspDnsRecordEntity> {

    IspDnsRecordEntity selectByDomain(String domain);

    List<IspDnsRecordEntity> selectByZoneId(Long zoneId);
}