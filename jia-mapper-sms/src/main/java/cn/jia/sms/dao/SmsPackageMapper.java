package cn.jia.sms.dao;

import cn.jia.sms.entity.SmsPackage;
import com.github.pagehelper.Page;

public interface SmsPackageMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(SmsPackage record);

    int insertSelective(SmsPackage record);

    SmsPackage selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(SmsPackage record);

    int updateByPrimaryKey(SmsPackage record);

    Page<SmsPackage> selectByExamplePage(SmsPackage example);
}