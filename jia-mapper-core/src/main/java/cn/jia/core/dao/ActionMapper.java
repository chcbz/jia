package cn.jia.core.dao;

import cn.jia.core.entity.Action;
import com.github.pagehelper.Page;

import java.util.List;

public interface ActionMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(Action record);

    int insertSelective(Action record);

    Action selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(Action record);

    int updateByPrimaryKey(Action record);

    Page<Action> selectAll();

    Page<Action> findByExamplePage(Action record);

    List<Action> selectByModule(String module);

    List<Action> selectByResourceId(String resourceId);
}