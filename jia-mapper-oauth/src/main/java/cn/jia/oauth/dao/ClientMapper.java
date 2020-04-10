package cn.jia.oauth.dao;

import cn.jia.oauth.entity.Client;

public interface ClientMapper {
    int deleteByPrimaryKey(String clientId);

    Client selectByPrimaryKey(String clientId);
    
    Client selectByAppcn(String appcn);
    
    int insert(Client record);

    int insertSelective(Client record);

    int updateByPrimaryKeySelective(Client record);

    int updateByPrimaryKey(Client record);
}