package cn.jia.task.service.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.task.dao.TaskPlanDao;
import cn.jia.task.entity.TaskPlanEntity;
import cn.jia.task.mapper.TaskPlanMapper;
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
public class TaskPlanDaoImpl extends BaseDaoImpl<TaskPlanMapper, TaskPlanEntity> implements TaskPlanDao {

}
