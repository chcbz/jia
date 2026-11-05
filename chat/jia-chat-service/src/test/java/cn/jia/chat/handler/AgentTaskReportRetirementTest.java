package cn.jia.chat.handler;

import cn.jia.chat.tool.AgentTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentTaskReportRetirementTest {
    @Test void modelToolsCannotCompleteOrFailAnEntireTask() {
        assertFalse(Arrays.stream(AgentTools.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(Tool.class)).filter(t -> t != null)
                .anyMatch(t -> "reportAgentTask".equals(t.name())));
    }
}
