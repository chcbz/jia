package cn.jia.task.service.impl;

import cn.jia.task.entity.TaskDetailVO;
import cn.jia.test.BaseDbUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskDetailDaoImplTest extends BaseDbUnitTest {
    @Inject
    private TaskDetailDaoImpl taskDetailDao;

    @Test
    public void selectByEntity() {
        TaskDetailVO taskDetailVO = new TaskDetailVO();
        taskDetailVO.setPlanId(20L);
        taskDetailVO.setTimeStart(1533830300L);
        assertEquals(15, taskDetailDao.selectByEntity(taskDetailVO).size());
    }

}