package cn.jia.task.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.task.entity.TaskPlanEntity;

public interface TaskPlanDao extends IBaseDao<TaskPlanEntity> {
	TaskPlanEntity findExactInOwnerScope(
			Long id, String tenantId, String clientId, String ownerJiacn);
}
