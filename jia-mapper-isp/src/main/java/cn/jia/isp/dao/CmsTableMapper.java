package cn.jia.isp.dao;

import com.github.pagehelper.Page;

import cn.jia.isp.entity.CmsTable;

public interface CmsTableMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(CmsTable record);

    int insertSelective(CmsTable record);

    CmsTable selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(CmsTable record);

    int updateByPrimaryKey(CmsTable record);

    CmsTable findByName(String name);
    
    Page<CmsTable> selectByExample(CmsTable example);
}