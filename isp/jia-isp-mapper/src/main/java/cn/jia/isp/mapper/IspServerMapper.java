package cn.jia.isp.mapper;

import cn.jia.isp.entity.IspServerEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

public interface IspServerMapper extends BaseMapper<IspServerEntity> {

    List<IspServerEntity> selectByExample(IspServerEntity example);
}