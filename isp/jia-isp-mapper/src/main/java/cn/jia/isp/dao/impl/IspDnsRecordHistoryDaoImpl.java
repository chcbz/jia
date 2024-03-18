package cn.jia.isp.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.isp.dao.IspDnsRecordHistoryDao;
import cn.jia.isp.entity.IspDnsRecordHistoryEntity;
import cn.jia.isp.mapper.IspDnsRecordHistoryMapper;
import jakarta.inject.Named;

@Named
public class IspDnsRecordHistoryDaoImpl extends BaseDaoImpl<IspDnsRecordHistoryMapper, IspDnsRecordHistoryEntity>
        implements IspDnsRecordHistoryDao {
}