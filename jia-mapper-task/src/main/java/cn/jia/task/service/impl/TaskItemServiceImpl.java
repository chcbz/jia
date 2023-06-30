package cn.jia.task.service.impl;

import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.task.entity.TaskItemEntity;
import cn.jia.task.mapper.TaskItemMapper;
import cn.jia.task.service.ITaskItemService;
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
public class TaskItemServiceImpl extends BaseServiceImpl<TaskItemMapper, TaskItemEntity> implements ITaskItemService {

}
