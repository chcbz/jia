package cn.jia.task.schedule;

import cn.jia.base.service.DictService;
import cn.jia.core.util.DateUtil;
import cn.jia.kefu.entity.KefuMsgTypeCode;
import cn.jia.kefu.service.KefuService;
import cn.jia.task.common.TaskConstants;
import cn.jia.task.entity.TaskDetailEntity;
import cn.jia.task.entity.TaskDetailVO;
import cn.jia.task.service.TaskService;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.service.UserService;
import com.github.pagehelper.PageInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TaskSchedule {

    @Autowired
    private TaskService taskService;
    @Autowired(required = false)
    private DictService dictService;
    @Autowired(required = false)
    private UserService userService;
    @Autowired(required = false)
    private KefuService kefuService;

    /**
     * 任务到期通知
     */
    @Scheduled(cron = "0 0/10 * * * ?")
    public void taskAlert() {
        TaskDetailVO example = new TaskDetailVO();
        example.setRemind(TaskConstants.TASK_REMIND_YES);
        example.setStatus(TaskConstants.COMMON_ENABLE);
        long now = DateUtil.nowTime();
        example.setTimeStart(now);
        example.setTimeEnd(now + 10 * 60 - 1);
        PageInfo<TaskDetailEntity> taskList;
        int pageNo = 1;
        do {
            taskList = taskService.findItems(example, pageNo++, 500);
            for (TaskDetailEntity vo : taskList.getList()) {
                UserEntity user = userService.findByJiacn(vo.getJiacn());
                if (user != null) {
                    String taskType = dictService.getValue(TaskConstants.DICT_TYPE_TASK_TYPE,
                            String.valueOf(vo.getType()));
                    try {
                        kefuService.sendTemplate(KefuMsgTypeCode.TASK, user.getNickname(), taskType,
                                vo.getName(), vo.getDescription());
                    } catch (Exception e) {
                        log.error("TaskSchedule.taskAlert", e);
                    }
                }
            }
        } while (taskList.isHasNextPage());
    }
}
