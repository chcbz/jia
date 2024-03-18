package cn.jia.task.entity;

import cn.jia.common.entity.BaseEntityWrapper;
import cn.jia.core.common.EsConstants;
import cn.jia.task.common.TaskConstants;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/**
 * <p>
 * 任务计划包装类
 * </p>
 * @since 2021-11-07
 */
public class TaskPlanEntityWrapper implements BaseEntityWrapper<TaskPlanVO, TaskPlanEntity> {
    @Override
    public void appendQueryWrapper(TaskPlanVO entity, QueryWrapper<TaskPlanEntity> wrapper) {
        Wrappers.lambdaQuery(TaskPlanEntity.class)
                .ge(entity.getStartTimeStart() != null, TaskPlanEntity::getStartTime, entity.getStartTimeStart())
                .lt(entity.getStartTimeEnd() != null, TaskPlanEntity::getStartTime, entity.getStartTimeEnd())
                .ge(entity.getEndTimeStart() != null, TaskPlanEntity::getEndTime, entity.getEndTimeStart())
                .lt(entity.getEndTimeEnd() != null, TaskPlanEntity::getEndTime, entity.getEndTimeEnd())
                .and(EsConstants.COMMON_YES.equals(entity.getHistoryFlag()), subQuery ->
                        subQuery.eq(TaskPlanEntity::getStatus, TaskConstants.TASK_STATUS_DISABLE).or()
                                .lt(TaskPlanEntity::getEndTime, System.currentTimeMillis()));
    }

    @Override
    public void appendUpdateWrapper(TaskPlanVO entity, UpdateWrapper<TaskPlanEntity> wrapper) {

    }
}
