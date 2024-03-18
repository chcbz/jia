package cn.jia.task.service.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.task.dao.TaskItemDao;
import cn.jia.task.entity.TaskItemEntity;
import cn.jia.task.mapper.TaskItemMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.inject.Named;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-11-07
 */
@Named
public class TaskItemDaoImpl extends BaseDaoImpl<TaskItemMapper, TaskItemEntity> implements TaskItemDao {

    @Override
    public int deleteByPlan(Long planId) {
        return baseMapper.delete(Wrappers.lambdaQuery(TaskItemEntity.class).eq(TaskItemEntity::getPlanId, planId));
    }

    @Override
    public int cancelByPlan(Long planId, Long time) {
        TaskItemEntity entity = new TaskItemEntity();
        entity.setStatus(0);
        return baseMapper.update(entity, Wrappers.lambdaUpdate(TaskItemEntity.class)
                .eq(TaskItemEntity::getPlanId, planId)
                .ge(TaskItemEntity::getExecuteTime, time));
    }
}
