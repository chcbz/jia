package cn.jia.task.service.impl;

import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.task.entity.TaskPlan;
import cn.jia.task.mapper.TaskPlanMapper;
import cn.jia.task.service.ITaskPlanService;
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
public class TaskPlanServiceImpl extends BaseServiceImpl<TaskPlanMapper, TaskPlan> implements ITaskPlanService {

}
