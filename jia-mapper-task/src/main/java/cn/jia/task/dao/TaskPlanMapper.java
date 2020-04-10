package cn.jia.task.dao;

import com.github.pagehelper.Page;

import cn.jia.task.entity.TaskPlan;
import cn.jia.task.entity.TaskPlanExample;

public interface TaskPlanMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(TaskPlan record);

    int insertSelective(TaskPlan record);

    TaskPlan selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(TaskPlan record);

    int updateByPrimaryKey(TaskPlan record);

    Page<TaskPlan> selectByExample(TaskPlanExample record);
}