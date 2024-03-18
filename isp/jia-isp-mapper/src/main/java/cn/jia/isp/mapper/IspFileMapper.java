package cn.jia.isp.mapper;

import cn.jia.isp.entity.IspFileEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

public interface IspFileMapper extends BaseMapper<IspFileEntity> {

    IspFileEntity selectByUri(String uri);

    List<IspFileEntity> selectByExample(IspFileEntity example);
}