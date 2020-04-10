package cn.jia.material.dao;

import cn.jia.material.entity.Media;
import cn.jia.material.entity.MediaExample;
import com.github.pagehelper.Page;

public interface MediaMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(Media record);

    int insertSelective(Media record);

    Media selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(Media record);

    int updateByPrimaryKey(Media record);

    Page<Media> selectAll();

    Page<Media> selectByExample(MediaExample example);
}