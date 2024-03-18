package cn.jia.task.service.impl;

import cn.jia.task.entity.TaskItemEntity;
import cn.jia.test.BaseDbUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskItemDaoImplTest extends BaseDbUnitTest {
    @Inject
    private TaskItemDaoImpl taskItemDao;

    @Test
    void deleteByPlan() {
        TaskItemEntity taskItemEntity = new TaskItemEntity();
        taskItemEntity.setPlanId(20L);
        assertEquals(15, taskItemDao.selectByEntity(taskItemEntity).size());
        taskItemDao.deleteByPlan(20L);
        assertEquals(0, taskItemDao.selectByEntity(taskItemEntity).size());
    }

    @Test
    void cancelByPlan() {
        TaskItemEntity taskItemEntity = new TaskItemEntity();
        taskItemEntity.setPlanId(21L);
        taskItemEntity.setStatus(1);
        assertEquals(2, taskItemDao.selectByEntity(taskItemEntity).size());
        taskItemDao.cancelByPlan(21L, 1533830300L);
        taskItemEntity.setStatus(0);
        assertEquals(2, taskItemDao.selectByEntity(taskItemEntity).size());
    }
}