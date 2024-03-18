package cn.jia.isp.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.isp.entity.IspDomainEntity;

import java.util.List;

public interface IspDomainDao extends IBaseDao<IspDomainEntity> {
    List<IspDomainEntity> selectByExample(IspDomainEntity example);
}