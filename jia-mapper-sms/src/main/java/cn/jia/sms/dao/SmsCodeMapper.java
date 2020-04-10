package cn.jia.sms.dao;

import cn.jia.sms.entity.SmsCode;

import java.util.List;

public interface SmsCodeMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(SmsCode record);

    int insertSelective(SmsCode record);

    SmsCode selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(SmsCode record);

    int updateByPrimaryKey(SmsCode record);
    
    List<SmsCode> selectByExample(SmsCode record);
}