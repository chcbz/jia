package cn.jia.task.service.impl;

import cn.jia.core.common.EsConstants;
import cn.jia.core.util.DateUtil;
import cn.jia.task.common.TaskConstants;
import cn.jia.task.dao.TaskDetailDao;
import cn.jia.task.dao.TaskItemDao;
import cn.jia.task.dao.TaskPlanDao;
import cn.jia.task.entity.TaskDetailEntity;
import cn.jia.task.entity.TaskDetailVO;
import cn.jia.task.entity.TaskItemEntity;
import cn.jia.task.entity.TaskPlanEntity;
import cn.jia.test.BaseMockTest;
import com.github.pagehelper.PageInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Calendar;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * 任务计划 服务层测试
 * chc
 */
class TaskServiceImplTest extends BaseMockTest {
    @Mock
    TaskPlanDao taskPlanDao;
    @Mock
    TaskItemDao taskItemDao;
    @Mock
    TaskDetailDao taskDetailDao;
    @InjectMocks
    TaskServiceImpl taskServiceImpl;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(taskServiceImpl, "baseDao", taskPlanDao);
    }

    @AfterEach
    void setDown() {
        verifyNoMoreInteractions(taskPlanDao, taskItemDao, taskDetailDao);
    }

    @Test
    void testCreate() {
        when(taskPlanDao.insert(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, TaskPlanEntity.class).setId(1L);
            return 1;
        });
        when(taskItemDao.insert(any())).thenReturn(1);

        // 长期
        TaskPlanEntity taskPlanEntity = new TaskPlanEntity();
        taskPlanEntity.setPeriod(TaskConstants.TASK_PERIOD_ALLTIME);
        TaskPlanEntity result = taskServiceImpl.create(taskPlanEntity);
        assertEquals(taskPlanEntity, result);

        verify(taskPlanDao, times(1)).insert(any());
        ArgumentCaptor<TaskItemEntity> taskItemEntityArgumentCaptor = ArgumentCaptor.forClass(TaskItemEntity.class);
        verify(taskItemDao, times(1)).insert(taskItemEntityArgumentCaptor.capture());
        assertEquals(taskItemEntityArgumentCaptor.getValue().getPlanId(), 1L);
    }

    @Test
    void testSaveItemByDate() {
        when(taskPlanDao.insert(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, TaskPlanEntity.class).setId(1L);
            return 1;
        });
        when(taskItemDao.insert(any())).thenReturn(1);

        // 指定日期
        TaskPlanEntity taskPlanEntity = new TaskPlanEntity();
        taskPlanEntity.setPeriod(TaskConstants.TASK_PERIOD_DATE);
        taskPlanEntity.setStartTime(DateUtil.nowTime());
        TaskPlanEntity result = taskServiceImpl.create(taskPlanEntity);
        assertEquals(taskPlanEntity, result);

        verify(taskPlanDao, times(1)).insert(any());
        ArgumentCaptor<TaskItemEntity> taskItemEntityArgumentCaptor = ArgumentCaptor.forClass(TaskItemEntity.class);
        verify(taskItemDao, times(1)).insert(taskItemEntityArgumentCaptor.capture());
        assertEquals(taskItemEntityArgumentCaptor.getValue().getPlanId(), 1L);
        assertEquals(taskItemEntityArgumentCaptor.getValue().getExecuteTime(), taskPlanEntity.getStartTime());
    }

    @Test
    void testSaveItemByMonthLunar() {
        when(taskPlanDao.insert(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, TaskPlanEntity.class).setId(1L);
            return 1;
        });
        when(taskItemDao.insert(any())).thenReturn(1);

        // 按月生成
        TaskPlanEntity taskPlanEntity = new TaskPlanEntity();
        taskPlanEntity.setPeriod(TaskConstants.TASK_PERIOD_MONTH);
        taskPlanEntity.setLunar(EsConstants.COMMON_YES);
        Calendar calendar = Calendar.getInstance();
        calendar.set(2019, Calendar.FEBRUARY, 1);
        taskPlanEntity.setStartTime(calendar.getTimeInMillis());
        calendar.add(Calendar.YEAR, 1);
        taskPlanEntity.setEndTime(calendar.getTimeInMillis());
        TaskPlanEntity result = taskServiceImpl.create(taskPlanEntity);
        assertEquals(taskPlanEntity, result);

        verify(taskPlanDao, times(1)).insert(any());
        ArgumentCaptor<TaskItemEntity> taskItemEntityArgumentCaptor = ArgumentCaptor.forClass(TaskItemEntity.class);
        verify(taskItemDao, times(13)).insert(taskItemEntityArgumentCaptor.capture());
        assertEquals(taskItemEntityArgumentCaptor.getValue().getPlanId(), 1L);
    }

    @Test
    void testSaveItemByPeriod() {
        when(taskPlanDao.insert(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, TaskPlanEntity.class).setId(1L);
            return 1;
        });
        when(taskItemDao.insert(any())).thenReturn(1);

        // 按日生成
        TaskPlanEntity taskPlanEntity = new TaskPlanEntity();
        taskPlanEntity.setPeriod(TaskConstants.TASK_PERIOD_DAY);
        Long now = DateUtil.nowTime();
        taskPlanEntity.setStartTime(now);
        taskPlanEntity.setEndTime(now + 365 * 24 * 60 * 60 * 1000L);
        TaskPlanEntity result = taskServiceImpl.create(taskPlanEntity);
        assertEquals(taskPlanEntity, result);

        verify(taskPlanDao, times(1)).insert(any());
        ArgumentCaptor<TaskItemEntity> taskItemEntityArgumentCaptor = ArgumentCaptor.forClass(TaskItemEntity.class);
        verify(taskItemDao, times(365)).insert(taskItemEntityArgumentCaptor.capture());
        assertEquals(taskItemEntityArgumentCaptor.getValue().getPlanId(), 1L);
    }

    @Test
    void testUpdate() {
        when(taskItemDao.insert(any())).thenReturn(1);

        when(taskPlanDao.updateById(any())).thenReturn(1);
        when(taskItemDao.deleteByPlan(anyLong())).thenReturn(1);

        // 长期
        TaskPlanEntity taskPlanEntity = new TaskPlanEntity();
        taskPlanEntity.setId(1L);
        taskPlanEntity.setPeriod(TaskConstants.TASK_PERIOD_ALLTIME);
        TaskPlanEntity result = taskServiceImpl.update(taskPlanEntity);
        assertEquals(taskPlanEntity, result);

        verify(taskPlanDao, times(1)).updateById(any());
        verify(taskItemDao, times(1)).deleteByPlan(1L);
        ArgumentCaptor<TaskItemEntity> taskItemEntityArgumentCaptor = ArgumentCaptor.forClass(TaskItemEntity.class);
        verify(taskItemDao, times(1)).insert(taskItemEntityArgumentCaptor.capture());
        assertEquals(taskItemEntityArgumentCaptor.getValue().getPlanId(), 1L);
    }

    @Test
    void testFindItems() {
        TaskDetailEntity taskDetailEntity = new TaskDetailEntity();
        when(taskDetailDao.selectByEntity(any())).thenReturn(List.of(taskDetailEntity));

        PageInfo<TaskDetailEntity> result = taskServiceImpl.findItems(new TaskDetailVO(), 0, 0, null);
        assertEquals(taskDetailEntity, result.getList().get(0));
    }

    @Test
    void testCancel() {
        when(taskPlanDao.updateById(any())).thenReturn(1);
        when(taskItemDao.cancelByPlan(anyLong(), anyLong())).thenReturn(1);

        taskServiceImpl.cancel(1L);
    }

}
