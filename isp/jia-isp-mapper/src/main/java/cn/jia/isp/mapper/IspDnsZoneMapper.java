package cn.jia.isp.mapper;

import cn.jia.isp.entity.IspDnsZoneEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

public interface IspDnsZoneMapper extends BaseMapper<IspDnsZoneEntity> {

    List<IspDnsZoneEntity> selectByExample(IspDnsZoneEntity record);

}