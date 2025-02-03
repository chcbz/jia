package cn.jia.task.entity;

import cn.jia.common.entity.BaseEntityWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

/**
 * <p>
 * 任务计划包装类
 * </p>
 * @since 2021-11-07
 */
public class TaskDetailVOWrapper implements BaseEntityWrapper<TaskDetailVO, TaskDetailEntity> {
    @Override
    public void appendQueryWrapper(TaskDetailVO entity, QueryWrapper<TaskDetailEntity> wrapper) {
        wrapper.lambda()
                .ge(entity.getTimeStart() != null, TaskDetailEntity::getExecuteTime, entity.getTimeStart())
                .lt(entity.getTimeEnd() != null, TaskDetailEntity::getExecuteTime, entity.getTimeEnd());
    }

    @Override
    public void appendUpdateWrapper(TaskDetailVO entity, UpdateWrapper<TaskDetailEntity> wrapper) {

    }
}
