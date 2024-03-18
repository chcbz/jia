package cn.jia.isp.mapper;

import cn.jia.isp.entity.CmsTableEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

public interface CmsTableMapper extends BaseMapper<CmsTableEntity> {

    CmsTableEntity findByName(String name);
    
    List<CmsTableEntity> selectByExample(CmsTableEntity example);
}