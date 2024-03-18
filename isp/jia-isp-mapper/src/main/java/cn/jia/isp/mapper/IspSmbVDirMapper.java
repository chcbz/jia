package cn.jia.isp.mapper;

import cn.jia.isp.entity.IspSmbVDirEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

public interface IspSmbVDirMapper extends BaseMapper<IspSmbVDirEntity> {

    List<IspSmbVDirEntity> selectByExample(IspSmbVDirEntity example);
}