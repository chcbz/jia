package cn.jia.material.dao;

import cn.jia.material.entity.PageViewLog;
import com.github.pagehelper.Page;

public interface PageViewLogMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(PageViewLog record);

    int insertSelective(PageViewLog record);

    PageViewLog selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(PageViewLog record);

    int updateByPrimaryKey(PageViewLog record);

    Page<PageViewLog> selectByExample(PageViewLog example);
}