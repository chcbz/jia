package cn.jia.sms.dao;

import cn.jia.sms.entity.SmsTemplateExample;
import com.github.pagehelper.Page;

import cn.jia.sms.entity.SmsTemplate;

public interface SmsTemplateMapper {
    int deleteByPrimaryKey(String templateId);

    int insert(SmsTemplate record);

    int insertSelective(SmsTemplate record);

    SmsTemplate selectByPrimaryKey(String templateId);

    int updateByPrimaryKeySelective(SmsTemplate record);

    int updateByPrimaryKey(SmsTemplate record);

    Page<SmsTemplate> selectByExamplePage(SmsTemplateExample record);
}