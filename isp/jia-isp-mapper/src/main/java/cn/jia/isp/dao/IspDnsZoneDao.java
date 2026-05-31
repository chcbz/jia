package cn.jia.isp.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.isp.entity.IspDnsZoneEntity;

import java.util.List;

public interface IspDnsZoneDao extends IBaseDao<IspDnsZoneEntity> {
    List<IspDnsZoneEntity> selectByExample(IspDnsZoneEntity record);
}