package cn.jia.task.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.task.entity.TaskItemEntity;

public interface TaskItemDao extends IBaseDao<TaskItemEntity> {
    int deleteByPlan(Long planId);

    int cancelByPlan(Long planId, Long time);
}
