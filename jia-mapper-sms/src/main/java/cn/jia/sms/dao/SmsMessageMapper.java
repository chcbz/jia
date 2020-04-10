package cn.jia.sms.dao;

import cn.jia.sms.entity.SmsMessage;

public interface SmsMessageMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(SmsMessage record);

    int insertSelective(SmsMessage record);

    SmsMessage selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(SmsMessage record);

    int updateByPrimaryKey(SmsMessage record);
}