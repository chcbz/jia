package cn.jia.isp.mapper;

import cn.jia.isp.entity.IspDomainEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

public interface IspDomainMapper extends BaseMapper<IspDomainEntity> {

    List<IspDomainEntity> selectByExample(IspDomainEntity example);
}