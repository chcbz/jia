package cn.jia.isp.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.isp.dao.CmsTableDao;
import cn.jia.isp.entity.CmsTableEntity;
import cn.jia.isp.mapper.CmsTableMapper;
import jakarta.inject.Named;

import java.util.List;

@Named
public class CmsTableDaoImpl extends BaseDaoImpl<CmsTableMapper, CmsTableEntity> implements CmsTableDao {
    @Override
    public CmsTableEntity findByName(String name) {
        return baseMapper.findByName(name);
    }

    @Override
    public List<CmsTableEntity> selectByEntity(CmsTableEntity example) {
        return baseMapper.selectByExample(example);
    }
}