package cn.jia.point.dao;

import cn.jia.point.entity.Gift;
import cn.jia.point.entity.GiftExample;
import com.github.pagehelper.Page;

public interface GiftMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(Gift record);

    int insertSelective(Gift record);

    Gift selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(Gift record);

    int updateByPrimaryKey(Gift record);

    Page<Gift> selectAll();

    Page<Gift> selectByExample(GiftExample example);
}