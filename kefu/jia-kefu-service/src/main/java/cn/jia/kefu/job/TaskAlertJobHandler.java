package cn.jia.kefu.job;

import cn.jia.base.service.DictService;
import cn.jia.core.util.DateUtil;
import cn.jia.kefu.entity.KefuMsgTypeCode;
import cn.jia.kefu.service.KefuService;
import cn.jia.task.common.TaskConstants;
import cn.jia.task.entity.TaskDetailEntity;
import cn.jia.task.entity.TaskDetailVO;
import cn.jia.task.job.JobContext;
import cn.jia.task.job.JobHandler;
import cn.jia.task.service.TaskService;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.service.UserService;
import com.github.pagehelper.PageInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 任务到期提醒处理器
 * 使用新的JobHandler架构实现任务提醒功能
 * <p>
 * 该处理器会定期检查到期任务，并通过客服模块发送通知
 *
 * @author chc
 */
@Slf4j
@Component
public class TaskAlertJobHandler implements JobHandler {

    private static final String JOB_NAME = "task_alert";
    private static final String GROUP = "task";
    private static final String DESCRIPTION = "任务到期提醒通知";
    private static final String CRON = "0 0/10 * * * ?"; // 每10分钟执行

    @Autowired(required = false)
    private TaskService taskService;
    @Autowired(required = false)
    private DictService dictService;
    @Autowired(required = false)
    private UserService userService;
    @Autowired(required = false)
    private KefuService kefuService;

    @Override
    public String getName() {
        return JOB_NAME;
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    @Override
    public String getGroup() {
        return GROUP;
    }

    @Override
    public String getCron() {
        return CRON;
    }

    @Override
    public boolean isParallel() {
        return true;
    }

    @Override
    public String execute(JobContext context) throws Exception {
        log.info("Starting task alert job, jobId: {}", context.getJobId());
        
        long startTime = System.currentTimeMillis();
        int processedCount = 0;
        int successCount = 0;
        int failCount = 0;

        TaskDetailVO example = new TaskDetailVO();
        example.setRemind(TaskConstants.TASK_REMIND_YES);
        example.setStatus(TaskConstants.COMMON_ENABLE);
        long now = DateUtil.nowTime();
        example.setTimeStart(now);
        example.setTimeEnd(now + 10 * 60 - 1); // 10分钟内到期
        
        PageInfo<TaskDetailEntity> taskList;
        int pageNum = 1;
        
        do {
            taskList = taskService.findItems(example, pageNum++, 500, null);
            for (TaskDetailEntity taskDetail : taskList.getList()) {
                processedCount++;
                try {
                    // 获取用户信息
                    UserEntity user = userService.findByJiacn(taskDetail.getJiacn());
                    if (user != null) {
                        // 获取任务类型名称
                        String taskType = dictService.getValue(TaskConstants.DICT_TYPE_TASK_TYPE,
                                String.valueOf(taskDetail.getType()));
                        
                        // 发送模板消息
                        kefuService.sendTemplate(KefuMsgTypeCode.TASK, user.getNickname(), taskType,
                                taskDetail.getName(), taskDetail.getDescription());
                        successCount++;
                        log.debug("Sent task alert for task: {}", taskDetail.getName());
                    }
                } catch (Exception e) {
                    failCount++;
                    log.error("Failed to send task alert for task: {}", taskDetail.getName(), e);
                }
            }
        } while (taskList.isHasNextPage());
        
        long duration = System.currentTimeMillis() - startTime;
        String result = String.format("Task alert job completed: processed=%d, success=%d, failed=%d, duration=%dms",
                processedCount, successCount, failCount, duration);
        log.info(result);
        
        return result;
    }
}