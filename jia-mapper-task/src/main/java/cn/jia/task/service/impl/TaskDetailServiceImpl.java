package cn.jia.task.service.impl;

import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.task.entity.TaskIDetailEntity;
import cn.jia.task.mapper.TaskDetailMapper;
import cn.jia.task.service.ITaskDetailService;
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
public class TaskDetailServiceImpl extends BaseServiceImpl<TaskDetailMapper, TaskIDetailEntity> implements ITaskDetailService {

}
