package cn.jia.tenantcard;

import cn.jia.task.common.TaskConstants;
import cn.jia.task.dao.TaskDetailDao;
import cn.jia.task.dao.TaskItemDao;
import cn.jia.task.dao.TaskPlanDao;
import cn.jia.task.entity.TaskItemEntity;
import cn.jia.task.entity.TaskPlanEntity;
import cn.jia.task.service.impl.TaskServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantCardT05Test {
    private static final long START_TIME = 1_704_067_200_000L;
    private static final long HOUR = 60 * 60 * 1000L;

    @Mock
    private TaskPlanDao taskPlanDao;
    @Mock
    private TaskItemDao taskItemDao;
    @Mock
    private TaskDetailDao taskDetailDao;
    @InjectMocks
    private TaskServiceImpl taskService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(taskService, "baseDao", taskPlanDao);
        when(taskPlanDao.insert(any(TaskPlanEntity.class))).thenReturn(1);
        when(taskItemDao.insert(any(TaskItemEntity.class))).thenReturn(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1"})
    void allTimeCopiesTenantAndClient(String tenantId) {
        TaskPlanEntity task = plan(101L, TaskConstants.TASK_PERIOD_ALLTIME, tenantId);

        taskService.create(task);

        assertInserted(task, null, TaskConstants.TASK_STATUS_ENABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1"})
    void dateCopiesTenantAndClient(String tenantId) {
        TaskPlanEntity task = plan(202L, TaskConstants.TASK_PERIOD_DATE, tenantId);
        task.setStartTime(START_TIME);

        taskService.create(task);

        assertInserted(task, START_TIME, TaskConstants.TASK_STATUS_ENABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1"})
    void lunarYearCopiesTenantAndClient(String tenantId) {
        TaskPlanEntity task = plan(303L, TaskConstants.TASK_PERIOD_YEAR, tenantId);
        task.setLunar(TaskConstants.COMMON_YES);
        task.setStartTime(START_TIME);
        task.setEndTime(START_TIME + HOUR);

        taskService.create(task);

        assertInserted(task, START_TIME, TaskConstants.TASK_STATUS_ENABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1"})
    void calendarRecurringCopiesTenantAndClient(String tenantId) {
        TaskPlanEntity task = plan(404L, TaskConstants.TASK_PERIOD_DAY, tenantId);
        task.setStartTime(START_TIME);
        task.setEndTime(START_TIME + HOUR);

        taskService.create(task);

        assertInserted(task, START_TIME, null);
    }

    private TaskPlanEntity plan(Long id, Integer period, String tenantId) {
        TaskPlanEntity task = new TaskPlanEntity();
        task.setId(id);
        task.setPeriod(period);
        task.setTenantId(tenantId);
        task.setClientId("client-test");
        task.setJiacn("owner-a");
        return task;
    }

    private void assertInserted(TaskPlanEntity task, Long executeTime, Integer status) {
        ArgumentCaptor<TaskItemEntity> captor = ArgumentCaptor.forClass(TaskItemEntity.class);
        verify(taskItemDao).insert(captor.capture());
        TaskItemEntity item = captor.getValue();
        assertEquals(task.getId(), item.getPlanId());
        assertEquals(task.getTenantId(), item.getTenantId());
        assertEquals(task.getClientId(), item.getClientId());
        assertEquals(executeTime, item.getExecuteTime());
        if (status == null) {
            assertNull(item.getStatus());
        } else {
            assertEquals(status, item.getStatus());
        }
    }
}
