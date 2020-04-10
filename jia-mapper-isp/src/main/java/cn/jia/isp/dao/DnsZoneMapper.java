package cn.jia.isp.dao;

import cn.jia.isp.entity.DnsZone;

import java.util.List;

public interface DnsZoneMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(DnsZone record);

    int insertSelective(DnsZone record);

    DnsZone selectByPrimaryKey(Integer id);
    
    List<DnsZone> selectByExample(DnsZone record);

    int updateByPrimaryKeySelective(DnsZone record);

    int updateByPrimaryKey(DnsZone record);
}