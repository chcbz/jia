package cn.jia.oauth.dao;

import cn.jia.oauth.entity.Resource;
import com.github.pagehelper.Page;

public interface ResourceMapper {
    int deleteByPrimaryKey(String resourceId);

    int insert(Resource record);

    int insertSelective(Resource record);

    Resource selectByPrimaryKey(String resourceId);

    int updateByPrimaryKeySelective(Resource record);

    int updateByPrimaryKey(Resource record);

    Page<Resource> selectByExamplePage(Resource record);
}