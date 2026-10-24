package cn.jia.chat.config;

import cn.jia.chat.memory.MemoryRepository;
import cn.jia.chat.service.ChatConversationService;
import cn.jia.chat.service.ConversationAdmissionService;
import cn.jia.chat.service.impl.AgentTaskThreadMemoryGuard;
import cn.jia.chat.tool.AgentTools;
import cn.jia.chat.tool.ChatTools;
import cn.jia.chat.tool.KefuTools;
import cn.jia.chat.tool.MaterialTools;
import cn.jia.chat.tool.PointTools;
import cn.jia.chat.tool.TaskTools;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ChatClientConfigTest {
    @Test
    void disabledDeploymentDoesNotResolveOrRegisterAnyModelTools() {
        ChatClientConfig config = new ChatClientConfig();
        ReflectionTestUtils.setField(config, "agentToolsEnabled", false);
        ReflectionTestUtils.setField(config, "mcpToolCallbacksEnabled", true);
        ReflectionTestUtils.setField(config, "skillsRootDirectories", new String[] { "/must-not-read" });

        ObjectProvider<McpSyncClient> mcp = provider();
        ObjectProvider<TaskTools> task = provider();
        ObjectProvider<AgentTools> agent = provider();
        ObjectProvider<PointTools> point = provider();
        ObjectProvider<MaterialTools> material = provider();
        ObjectProvider<KefuTools> kefu = provider();
        ObjectProvider<ChatTools> chatTools = provider();

        ChatClient client = config.chatClient(
                ChatClient.builder(mock(ChatModel.class)),
                mcp,
                mock(MemoryRepository.class),
                mock(AgentTaskThreadMemoryGuard.class),
                mock(ChatConversationService.class),
                mock(ConversationAdmissionService.class),
                task, agent, point, material, kefu, chatTools);

        assertNotNull(client);
        verifyNoInteractions(mcp, task, agent, point, material, kefu, chatTools);
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider() {
        return mock(ObjectProvider.class);
    }
}
