package cn.jia.isp.dao;

import com.github.pagehelper.Page;

import cn.jia.isp.entity.CmsColumn;

public interface CmsColumnMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(CmsColumn record);

    int insertSelective(CmsColumn record);

    CmsColumn selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(CmsColumn record);

    int updateByPrimaryKey(CmsColumn record);

    Page<CmsColumn> selectByExample(CmsColumn example);
}