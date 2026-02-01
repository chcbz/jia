package cn.jia.task.service;

import cn.jia.common.service.IBaseService;
import cn.jia.task.entity.TaskDetailEntity;
import cn.jia.task.entity.TaskDetailVO;
import cn.jia.task.entity.TaskPlanEntity;
import com.github.pagehelper.PageInfo;

public interface TaskService extends IBaseService<TaskPlanEntity> {
	void cancel(Long id);

	PageInfo<TaskDetailEntity> findItems(TaskDetailVO example, int pageNum, int pageSize, String orderBy);
}
