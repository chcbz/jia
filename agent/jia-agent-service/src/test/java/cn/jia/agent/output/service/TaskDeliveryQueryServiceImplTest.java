package cn.jia.agent.output.service;

import cn.jia.agent.config.OutputDeliveryProperties;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.output.OutputDeliveryException;
import cn.jia.agent.output.dao.TaskDeliveryDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskDeliveryQueryServiceImplTest {
    private AgentTaskMetaDao taskDao;
    private TaskDeliveryDao deliveryDao;
    private TaskDeliveryQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        taskDao = mock(AgentTaskMetaDao.class);
        deliveryDao = mock(TaskDeliveryDao.class);
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:delivery_query_" + System.nanoTime());
        OutputDeliveryProperties properties = new OutputDeliveryProperties(
                true, null, null, "stable-query-test-key", "bucket",
                "localhost", 3310, 10);
        service = new TaskDeliveryQueryServiceImpl(taskDao, deliveryDao,
                new DataSourceTransactionManager(dataSource), properties);
    }

    @Test
    void signedCursorIsScopeBoundAndTamperEvident() {
        AgentTaskMetaEntity task = task("delivery-b", 2L);
        when(taskDao.findByTaskId("owner", "client", "task-1")).thenReturn(task);
        long now = System.currentTimeMillis() - 10;
        TaskDeliveryDao.DeliveryRow first = row(
                "delivery-b", 2, now, "SUBMITTED", null);
        TaskDeliveryDao.DeliveryRow second = row(
                "delivery-a", 1, now - 1, "CHANGES_REQUESTED", now);
        when(deliveryDao.listTaskDeliveries(eq("owner"), eq("client"), eq("task-1"),
                anyLong(), isNull(), isNull(), eq(2))).thenReturn(List.of(first, second));
        when(deliveryDao.listTaskDeliveries(eq("owner"), eq("client"), eq("task-1"),
                anyLong(), eq(now), eq("delivery-b"), eq(2))).thenReturn(List.of(second));
        when(deliveryDao.listItems("owner", "client", "delivery-b", false))
                .thenReturn(List.of(new TaskDeliveryDao.ItemRow(
                        "owner", "client", "delivery-b", "artifact-1", 3,
                        new byte[32], null, "result", 0, 0)));
        when(deliveryDao.listItems("owner", "client", "delivery-a", false))
                .thenReturn(List.of(new TaskDeliveryDao.ItemRow(
                        "owner", "client", "delivery-a", "artifact-old", 1,
                        new byte[32], null, "previous result", 0, 0)));

        var page = service.list("owner", "client", "owner", "task-1", null, 1);
        assertEquals(1, page.items().size());
        assertNotNull(page.nextCursor());
        assertEquals(List.of("accept", "request_changes"),
                page.items().getFirst().reviewActions());
        String token = page.nextCursor();
        var secondPage = service.list(
                "owner", "client", "owner", "task-1", token, 1);
        assertEquals(page.snapshotAt(), secondPage.snapshotAt());
        assertEquals("delivery-a", secondPage.items().getFirst().deliveryId());
        assertEquals(List.of(), secondPage.items().getFirst().reviewActions());
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");
        assertEquals(400, assertThrows(OutputDeliveryException.class,
                () -> service.list("owner", "client", "owner", "task-1", tampered, 1))
                .status());
        assertEquals(400, assertThrows(OutputDeliveryException.class,
                () -> service.list("owner", "other-client", "owner",
                        "task-1", token, 1)).status());
    }

    @Test
    void ownerMismatchIsHidden() {
        AgentTaskMetaEntity task = new AgentTaskMetaEntity()
                .setTaskId("task-1").setTaskVersion(1L);
        task.setTenantId("owner");
        task.setClientId("client");
        when(taskDao.findByTaskId(any(), any(), any())).thenReturn(task);
        assertEquals(404, assertThrows(OutputDeliveryException.class,
                () -> service.list("attacker", "client", "attacker",
                        "task-1", null, 20)).status());
    }

    private TaskDeliveryDao.DeliveryRow row(String deliveryId, long revision, long submittedAt) {
        return new TaskDeliveryDao.DeliveryRow(
                "owner", "client", deliveryId, "task-1", "work-1", revision,
                null, "agent-1", "run-1", "done", "SUBMITTED", submittedAt,
                null, "manifest-1", 1, 0);
    }
}
