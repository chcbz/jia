package cn.jia.isp.mapper;

import cn.jia.isp.entity.CmsColumnEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

public interface CmsColumnMapper extends BaseMapper<CmsColumnEntity> {

    List<CmsColumnEntity> selectByExample(CmsColumnEntity example);
}