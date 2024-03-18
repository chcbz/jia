package cn.jia.isp.mapper;

import cn.jia.isp.entity.*;

import java.util.List;
import java.util.Map;

public interface CmsRowMapper {
	
    int deleteById(CmsRowDTO record);

    int insert(CmsRowDTO record);

    Map<String, Object> selectById(CmsRowDTO record);

    int updateById(CmsRowDTO record);

    List<Map<String, Object>> selectByExample(CmsRowExample example);
    
    int createTable(CmsTableDTO record);
    
    int dropTable(CmsTableEntity record);

    int addColumn(CmsColumnDTO record);

    int modifyColumn(CmsColumnDTO record);

    int dropColumn(CmsColumnDTO record);
}