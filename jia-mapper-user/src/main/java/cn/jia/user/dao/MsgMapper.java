package cn.jia.user.dao;

import com.github.pagehelper.Page;

import cn.jia.user.entity.Msg;

public interface MsgMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(Msg record);

    int insertSelective(Msg record);

    Msg selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(Msg record);

    int updateByPrimaryKey(Msg record);

    Page<Msg> selectByExamplePage(Msg record);

    int updateByUserId(Msg record);
}