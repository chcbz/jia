package cn.jia.agent.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskEventCatalogTest {

    @Test
    void c01bFrozenEventCatalogIsKnown() {
        List.of(
                "TASK_ASSIGNED", "TASK_STARTED", "TASK_BLOCKED", "TASK_ARCHIVED",
                "MEMBER_WORKING", "MEMBER_DONE", "MEMBER_FAILED", "MEMBER_LEFT",
                "WORK_ITEM_BLOCKED", "WORK_ITEM_FAILED", "WORK_ITEM_CANCELLED",
                "WORK_ITEM_LEASE_RENEWED", "WORK_ITEM_LEASE_RELEASED",
                "REQUEST_CREATED", "REQUEST_ACKNOWLEDGED", "REQUEST_RESOLVED",
                "REQUEST_REJECTED", "REQUEST_CANCELLED",
                "THREAD_CREATED", "MESSAGE_POSTED"
        ).forEach(type -> assertEquals(type, TaskEventType.requireKnown(type)));
    }

    @Test
    void threadAndMessageAggregatesAreKnownAndCatalogRemainsFailClosed() {
        assertEquals("thread", TaskEventType.Aggregate.requireKnown("thread"));
        assertEquals("message", TaskEventType.Aggregate.requireKnown("message"));
        assertThrows(IllegalArgumentException.class,
                () -> TaskEventType.requireKnown("LEASE_TOKEN_RECORDED"));
        assertThrows(IllegalArgumentException.class,
                () -> TaskEventType.Aggregate.requireKnown("conversation_content"));
    }
}
