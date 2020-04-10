package cn.jia.isp.dao;

import cn.jia.isp.entity.*;
import com.github.pagehelper.Page;

import java.util.Map;

public interface CmsRowMapper {
	
    int deleteByPrimaryKey(CmsRowDTO record);

    int insertSelective(CmsRowDTO record);

    Map<String, Object> selectByPrimaryKey(CmsRowDTO record);

    int updateByPrimaryKeySelective(CmsRowDTO record);

    Page<Map<String, Object>> selectByExample(CmsRowExample example);
    
    int createTable(CmsTableDTO record);
    
    int dropTable(CmsTable record);

    int addColumn(CmsColumnDTO record);

    int modifyColumn(CmsColumnDTO record);

    int dropColumn(CmsColumnDTO record);
}