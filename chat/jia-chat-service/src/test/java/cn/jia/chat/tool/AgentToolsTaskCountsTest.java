package cn.jia.chat.tool;

import cn.jia.agent.entity.AgentTaskCountsDTO;
import cn.jia.agent.entity.AgentTaskSearchDTO;
import cn.jia.agent.service.AgentService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolsTaskCountsTest {
    @Test
    void toolPreservesTheSameAuthoritativeTwoMapContractWithoutNumericCoercion() {
        var service = mock(AgentService.class);
        var expected = new AgentTaskCountsDTO(
                Map.of("PENDING_ASSIGNMENT", "9007199254740993", "total", "9007199254740993"),
                Map.of("open", "9007199254740993", "total", "9007199254740993"));
        when(service.countTasksByStatus(any())).thenReturn(expected);
        var actual = new AgentTools(service).countTasksByStatus();
        assertSame(expected, actual);
        var search = ArgumentCaptor.forClass(AgentTaskSearchDTO.class);
        verify(service).countTasksByStatus(search.capture());
        assertNull(search.getValue().getPhase());
        assertNull(search.getValue().getStatus());
    }
}
