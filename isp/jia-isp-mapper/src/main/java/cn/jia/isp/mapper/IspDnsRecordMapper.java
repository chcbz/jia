package cn.jia.isp.mapper;

import cn.jia.isp.entity.IspDnsRecordEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

public interface IspDnsRecordMapper extends BaseMapper<IspDnsRecordEntity> {

    IspDnsRecordEntity selectByDomain(String domain);

    List<IspDnsRecordEntity> selectByZoneId(Long zoneId);
}