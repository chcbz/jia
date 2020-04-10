package cn.jia.task.dao;

import cn.jia.task.entity.TaskItem;
import org.apache.ibatis.annotations.Param;

public interface TaskItemMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(TaskItem record);

    int insertSelective(TaskItem record);

    TaskItem selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(TaskItem record);

    int updateByPrimaryKey(TaskItem record);
    
    int deleteByPlan(Integer planId);
    
    int cancelByPlan(@Param("planId") Integer planId, @Param("time") Long time);
}