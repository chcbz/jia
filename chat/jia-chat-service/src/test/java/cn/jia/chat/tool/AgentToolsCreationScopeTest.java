package cn.jia.chat.tool;

import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.service.AgentService;
import cn.jia.core.context.EsContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentToolsCreationScopeTest {
    @AfterEach void cleanup() { EsContextHolder.clearContext(); }

    @Test
    void invocationTenantAndOwnerAreNeverReplacedByProductionDefaults() {
        var service = mock(AgentService.class);
        EsContextHolder.clearContext();
        var scope = new AgentService.TaskOwnerScope("1", "client-a", "owner-a");
        var context = new org.springframework.ai.chat.model.ToolContext(
                java.util.Map.of(AgentTools.TASK_OWNER_SCOPE, scope));
        var callback = java.util.Arrays.stream(org.springframework.ai.support.ToolCallbacks.from(
                new AgentTools(service)))
                .filter(tool -> tool.getToolDefinition().name().equals("createAgentTask"))
                .findFirst().orElseThrow();
        callback.call("{\"title\":\"title\",\"description\":\"body\",\"requiredAbilities\":[\"research\"],\"reward\":5}", context);
        var request = ArgumentCaptor.forClass(AgentTaskCreateDTO.class);
        verify(service).createTask(eq(new AgentService.TaskOwnerScope("1", "client-a", "owner-a")),
                request.capture());
        assertEquals("title", request.getValue().getTitle());
        assertEquals(5, request.getValue().getReward());
    }
    @Test
    void absentOrModelShapedContextNeverCreatesTask() {
        var service = mock(AgentService.class);
        var tool = new AgentTools(service);
        for (var context : java.util.Arrays.asList(null,
                new org.springframework.ai.chat.model.ToolContext(java.util.Map.of()),
                new org.springframework.ai.chat.model.ToolContext(java.util.Map.of(
                        AgentTools.TASK_OWNER_SCOPE, java.util.Map.of("tenantId", "0"))))) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> tool.createAgentTask("title", "body", List.of(), 0, context));
        }
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    void toolDefinitionDoesNotExposeTrustedIdentityAsModelInput() {
        var tool = java.util.Arrays.stream(org.springframework.ai.support.ToolCallbacks.from(
                new AgentTools(mock(AgentService.class))))
                .filter(callback -> callback.getToolDefinition().name().equals("createAgentTask"))
                .findFirst().orElseThrow();
        String schema = tool.getToolDefinition().inputSchema();
        org.junit.jupiter.api.Assertions.assertFalse(schema.contains("toolContext"));
        org.junit.jupiter.api.Assertions.assertFalse(schema.contains("tenantId"));
        org.junit.jupiter.api.Assertions.assertFalse(schema.contains("ownerJiacn"));
    }
}
