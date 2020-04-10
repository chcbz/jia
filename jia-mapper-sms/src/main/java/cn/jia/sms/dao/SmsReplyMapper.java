package cn.jia.sms.dao;

import com.github.pagehelper.Page;

import cn.jia.sms.entity.SmsReply;

public interface SmsReplyMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(SmsReply record);

    int insertSelective(SmsReply record);

    SmsReply selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(SmsReply record);

    int updateByPrimaryKey(SmsReply record);
    
    Page<SmsReply> selectByExamplePage(SmsReply record);
}