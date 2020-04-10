package cn.jia.task.dao;

import cn.jia.task.entity.TaskItemVO;
import cn.jia.task.entity.TaskItemVOExample;
import com.github.pagehelper.Page;

public interface TaskItemVOMapper {
    Page<TaskItemVO> selectByExample(TaskItemVOExample example);
}