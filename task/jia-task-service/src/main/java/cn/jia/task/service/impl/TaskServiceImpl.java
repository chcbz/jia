package cn.jia.task.service.impl;

import cn.jia.core.service.BaseServiceImpl;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.LunarUtil;
import cn.jia.task.common.TaskConstants;
import cn.jia.task.dao.TaskDetailDao;
import cn.jia.task.dao.TaskItemDao;
import cn.jia.task.dao.TaskPlanDao;
import cn.jia.task.entity.*;
import cn.jia.task.service.TaskService;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Calendar;

/**
 * @author chc
 */
@Service
public class TaskServiceImpl extends BaseServiceImpl<TaskPlanDao, TaskPlanEntity> implements TaskService {
	@Autowired
	private TaskItemDao taskItemDao;
	@Autowired
	private TaskDetailDao taskDetailDao;

	@Override
	public TaskPlanEntity create(TaskPlanEntity task) {
		//保存任务信息
		baseDao.insert(task);
		//保存任务执行明细
		saveItems(task);
		return task;
	}

	/**
	 * 保存任务执行明细
	 *
	 * @param task 任务计划
	 */
	private void saveItems(TaskPlanEntity task) {
		if(TaskConstants.TASK_PERIOD_ALLTIME.equals(task.getPeriod())) {
			TaskItemEntity item = new TaskItemEntity();
			item.setPlanId(task.getId());
			item.setStatus(TaskConstants.TASK_STATUS_ENABLE);
			taskItemDao.insert(item);
		} else if(TaskConstants.TASK_PERIOD_DATE.equals(task.getPeriod())) {
			TaskItemEntity item = new TaskItemEntity();
			item.setPlanId(task.getId());
			item.setStatus(TaskConstants.TASK_STATUS_ENABLE);
			item.setExecuteTime(task.getStartTime());
			taskItemDao.insert(item);
		} else {
			Long time = task.getStartTime();
			Long endTime = task.getEndTime();
			Calendar calendar = Calendar.getInstance();
			calendar.setTimeInMillis(time);
			LunarUtil.Lunar lunar = LunarUtil.solarToLunar(calendar.getTime());
			int lunarMonth = lunar.lunarMonth;
			int lunarDay = lunar.lunarDay;
			do {
				Calendar c = Calendar.getInstance();
				c.setTimeInMillis(time);
				if (TaskConstants.COMMON_YES.equals(task.getLunar())) {
					LunarUtil.Lunar l = LunarUtil.solarToLunar(c.getTime());
					int m = l.lunarMonth;
					int d = l.lunarDay;
					if((TaskConstants.TASK_PERIOD_YEAR.equals(task.getPeriod()) && lunarMonth == m && lunarDay == d) ||
							(TaskConstants.TASK_PERIOD_MONTH.equals(task.getPeriod()) && lunarDay == d)) {
						//保存当前执行点
						TaskItemEntity item = new TaskItemEntity();
						item.setPlanId(task.getId());
						item.setStatus(TaskConstants.TASK_STATUS_ENABLE);
						item.setExecuteTime(time);
						taskItemDao.insert(item);
					}
					//设置下一个执行点
					c.add(Calendar.DAY_OF_MONTH, 1);
				} else {
					//保存当前执行点
					TaskItemEntity item = new TaskItemEntity();
					item.setPlanId(task.getId());
					item.setExecuteTime(time);
					taskItemDao.insert(item);
					//设置下一个执行点
					//noinspection MagicConstant
					c.add(task.getPeriod(), 1);
				}
				time = c.getTimeInMillis();
			} while (time < endTime);
		}
	}

	@Override
	public TaskPlanEntity update(TaskPlanEntity task) {
		//保存任务信息
		baseDao.updateById(task);
		//清空原有任务执行明细
		taskItemDao.deleteByPlan(task.getId());
		//保存任务执行明细
		saveItems(task);
		return task;
	}

	@Override
	public PageInfo<TaskDetailEntity> findItems(TaskDetailVO example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return PageInfo.of(taskDetailDao.selectByEntity(example));
	}

	@Override
	public void cancel(Long id) {
		//失效当前任务
		TaskPlanEntity task = new TaskPlanEntity();
		task.setId(id);
		task.setStatus(TaskConstants.TASK_STATUS_DISABLE);
		baseDao.updateById(task);
		//失效还没有执行的任务明细
		Long now = DateUtil.nowTime();
		taskItemDao.cancelByPlan(id, now);
	}

}
