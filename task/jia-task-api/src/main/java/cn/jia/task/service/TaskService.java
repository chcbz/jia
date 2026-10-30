package cn.jia.task.service;

import cn.jia.common.service.IBaseService;
import cn.jia.task.entity.TaskDetailEntity;
import cn.jia.task.entity.TaskDetailVO;
import cn.jia.task.entity.TaskPlanEntity;
import com.github.pagehelper.PageInfo;

public interface TaskService extends IBaseService<TaskPlanEntity> {
	/** Exact private plan projection; scope must come from a trusted owner boundary. */
	TaskPlanEntity getInOwnerScope(String tenantId, String clientId, String ownerJiacn, Long id);

	void cancel(Long id);

	PageInfo<TaskDetailEntity> findItems(TaskDetailVO example, int pageNum, int pageSize, String orderBy);
}
