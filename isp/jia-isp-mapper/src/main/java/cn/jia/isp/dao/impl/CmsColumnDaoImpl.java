package cn.jia.isp.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.isp.dao.CmsColumnDao;
import cn.jia.isp.entity.CmsColumnEntity;
import cn.jia.isp.mapper.CmsColumnMapper;
import jakarta.inject.Named;

import java.util.List;

@Named
public class CmsColumnDaoImpl extends BaseDaoImpl<CmsColumnMapper, CmsColumnEntity> implements CmsColumnDao {

    @Override
    public List<CmsColumnEntity> selectByEntity(CmsColumnEntity example) {
        return baseMapper.selectByExample(example);
    }
}