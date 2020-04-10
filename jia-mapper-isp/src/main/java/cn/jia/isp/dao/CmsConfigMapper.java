package cn.jia.isp.dao;

import cn.jia.isp.entity.CmsConfig;

public interface CmsConfigMapper {
    int deleteByPrimaryKey(String clientId);

    int insert(CmsConfig record);

    int insertSelective(CmsConfig record);

    CmsConfig selectByPrimaryKey(String clientId);

    int updateByPrimaryKeySelective(CmsConfig record);

    int updateByPrimaryKey(CmsConfig record);
}