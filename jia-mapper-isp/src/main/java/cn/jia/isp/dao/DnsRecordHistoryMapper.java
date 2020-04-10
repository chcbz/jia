package cn.jia.isp.dao;

import cn.jia.isp.entity.DnsRecordHistory;

public interface DnsRecordHistoryMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(DnsRecordHistory record);

    int insertSelective(DnsRecordHistory record);

    DnsRecordHistory selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(DnsRecordHistory record);

    int updateByPrimaryKey(DnsRecordHistory record);
}