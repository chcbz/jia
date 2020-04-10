package cn.jia.point.dao;

import cn.jia.point.entity.Sign;

public interface SignMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(Sign record);

    int insertSelective(Sign record);

    Sign selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(Sign record);

    int updateByPrimaryKey(Sign record);
    
    Sign selectLast(String jiacn);
}