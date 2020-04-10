package cn.jia.material.dao;

import cn.jia.material.entity.Tip;
import com.github.pagehelper.Page;

public interface TipMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(Tip record);

    int insertSelective(Tip record);

    Tip selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(Tip record);

    int updateByPrimaryKey(Tip record);

    Page<Tip> selectByExample(Tip example);
}