package cn.jia.task.entity;

import cn.jia.common.entity.BaseEntityWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/**
 * <p>
 * 任务计划包装类
 * </p>
 * @since 2021-11-07
 */
public class TaskDetailEntityWrapper implements BaseEntityWrapper<TaskDetailVO, TaskDetailEntity> {
    @Override
    public void appendQueryWrapper(TaskDetailVO entity, QueryWrapper<TaskDetailEntity> wrapper) {
        Wrappers.lambdaQuery(TaskDetailEntity.class)
                .ge(entity.getTimeStart() != null, TaskDetailEntity::getCreateTime, entity.getTimeStart())
                .lt(entity.getTimeEnd() != null, TaskDetailEntity::getCreateTime, entity.getTimeEnd());
    }

    @Override
    public void appendUpdateWrapper(TaskDetailVO entity, UpdateWrapper<TaskDetailEntity> wrapper) {

    }
}
