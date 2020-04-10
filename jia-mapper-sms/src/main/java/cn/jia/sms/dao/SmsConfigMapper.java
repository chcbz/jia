package cn.jia.sms.dao;

import cn.jia.sms.entity.SmsConfig;

public interface SmsConfigMapper {
    int deleteByPrimaryKey(String clientId);

    int insert(SmsConfig record);

    int insertSelective(SmsConfig record);

    SmsConfig selectByPrimaryKey(String clientId);

    int updateByPrimaryKeySelective(SmsConfig record);

    int updateByPrimaryKey(SmsConfig record);
}