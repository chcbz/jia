package cn.jia.isp.dao;

import java.util.List;

import cn.jia.isp.entity.DnsRecord;

public interface DnsRecordMapper {
	int deleteByPrimaryKey(Integer id);

    int insert(DnsRecord record);

    int insertSelective(DnsRecord record);

    DnsRecord selectByPrimaryKey(Integer id);
    
    DnsRecord selectByDomain(String domain);

    int updateByPrimaryKeySelective(DnsRecord record);

    int updateByPrimaryKey(DnsRecord record);
    
    List<DnsRecord> selectByZoneId(Integer zoneId);
}