package cn.jia.kefu.dao;

import cn.jia.kefu.entity.KefuFAQ;
import cn.jia.kefu.entity.KefuFAQExample;
import com.github.pagehelper.Page;

public interface KefuFAQMapper {

    int deleteByPrimaryKey(Integer id);

    int insert(KefuFAQ record);

    int insertSelective(KefuFAQ record);

    KefuFAQ selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(KefuFAQ record);

    int updateByPrimaryKey(KefuFAQ record);

    Page<KefuFAQ> selectByExample(KefuFAQExample example);
}