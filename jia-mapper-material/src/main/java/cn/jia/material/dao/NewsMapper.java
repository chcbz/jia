package cn.jia.material.dao;

import cn.jia.material.entity.News;
import cn.jia.material.entity.NewsExample;
import com.github.pagehelper.Page;

public interface NewsMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(News record);

    int insertSelective(News record);

    News selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(News record);

    int updateByPrimaryKey(News record);

    Page<News> selectAll();

    Page<News> selectByExample(NewsExample example);
}