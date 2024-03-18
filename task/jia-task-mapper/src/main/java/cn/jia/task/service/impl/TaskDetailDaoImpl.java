package cn.jia.task.service.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.core.dao.IBaseDao;
import cn.jia.task.dao.TaskDetailDao;
import cn.jia.task.entity.TaskDetailEntity;
import cn.jia.task.mapper.TaskDetailMapper;
import jakarta.inject.Named;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-11-07
 */
@Named
public class TaskDetailDaoImpl extends BaseDaoImpl<TaskDetailMapper, TaskDetailEntity> implements TaskDetailDao {

}
