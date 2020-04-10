package cn.jia.kefu.dao;

import cn.jia.kefu.entity.KefuMessage;
import cn.jia.kefu.entity.KefuMessageExample;
import com.github.pagehelper.Page;

public interface KefuMessageMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(KefuMessage record);

    int insertSelective(KefuMessage record);

    KefuMessage selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(KefuMessage record);

    int updateByPrimaryKey(KefuMessage record);

    Page<KefuMessage> selectByExample(KefuMessageExample example);
}