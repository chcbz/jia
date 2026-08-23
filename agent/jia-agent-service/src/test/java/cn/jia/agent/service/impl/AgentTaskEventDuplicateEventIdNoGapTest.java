package cn.jia.agent.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** C01B-E22 recovery evidence for duplicate event IDs without committed version holes. */
class AgentTaskEventDuplicateEventIdNoGapTest {

    @Test
    void h2DuplicateEventIdFailureLeavesNextCommittedVersionGapFree() throws Exception {
        verifyDuplicateFailureLeavesNoGap(AgentTaskEventTestFixture.h2("duplicate"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "C01B_MYSQL_URL", matches = ".+")
    void mysqlDuplicateEventIdFailureLeavesNextCommittedVersionGapFree() throws Exception {
        verifyDuplicateFailureLeavesNoGap(AgentTaskEventTestFixture.mysql("duplicate"));
    }

    private void verifyDuplicateFailureLeavesNoGap(AgentTaskEventTestFixture fixture) {
        try (fixture) {
            fixture.seedTask();
            fixture.writer.append(fixture.command("evt-stable"));

            assertThrows(RuntimeException.class,
                    () -> fixture.writer.append(fixture.command("evt-stable")));
            assertEquals(List.of(1L), fixture.eventVersions());
            assertEquals(1L, fixture.currentEventVersion());
            assertEquals(1L, fixture.maxEventVersion());

            fixture.writer.append(fixture.command("evt-next"));
            assertEquals(List.of(1L, 2L), fixture.eventVersions());
            assertEquals(2, fixture.eventCount());
            assertEquals(2L, fixture.currentEventVersion());
            assertEquals(fixture.maxEventVersion(), fixture.currentEventVersion());
        }
    }
}
