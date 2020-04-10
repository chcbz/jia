package cn.jia.kefu.dao;

import cn.jia.kefu.entity.KefuMsgType;
import cn.jia.kefu.entity.KefuMsgTypeExample;
import com.github.pagehelper.Page;

public interface KefuMsgTypeMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(KefuMsgType record);

    int insertSelective(KefuMsgType record);

    KefuMsgType selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(KefuMsgType record);

    int updateByPrimaryKey(KefuMsgType record);

    Page<KefuMsgType> selectByExample(KefuMsgTypeExample example);
}