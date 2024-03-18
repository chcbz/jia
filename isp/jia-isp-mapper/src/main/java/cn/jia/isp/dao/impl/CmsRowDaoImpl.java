package cn.jia.isp.dao.impl;

import cn.jia.isp.dao.CmsRowDao;
import cn.jia.isp.entity.*;
import cn.jia.isp.mapper.CmsRowMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.List;
import java.util.Map;

@Named
public class CmsRowDaoImpl implements CmsRowDao {
    @Inject
    private CmsRowMapper baseMapper;

    @Override
    public int deleteById(CmsRowDTO record) {
        return baseMapper.deleteById(record);
    }

    @Override
    public int insert(CmsRowDTO record) {
        return baseMapper.insert(record);
    }

    @Override
    public Map<String, Object> selectById(CmsRowDTO record) {
        return baseMapper.selectById(record);
    }

    @Override
    public int updateById(CmsRowDTO record) {
        return baseMapper.updateById(record);
    }

    @Override
    public List<Map<String, Object>> selectByExample(CmsRowExample example) {
        return baseMapper.selectByExample(example);
    }

    @Override
    public int createTable(CmsTableDTO record) {
        return baseMapper.createTable(record);
    }

    @Override
    public int dropTable(CmsTableEntity record) {
        return baseMapper.dropTable(record);
    }

    @Override
    public int addColumn(CmsColumnDTO record) {
        return baseMapper.addColumn(record);
    }

    @Override
    public int modifyColumn(CmsColumnDTO record) {
        return baseMapper.modifyColumn(record);
    }

    @Override
    public int dropColumn(CmsColumnDTO record) {
        return baseMapper.dropColumn(record);
    }
}