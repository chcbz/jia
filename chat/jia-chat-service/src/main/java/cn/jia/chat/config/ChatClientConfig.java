package cn.jia.chat.config;

import java.util.List;

import cn.jia.chat.advisor.DatabaseChatMemoryAdvisor;
import cn.jia.chat.advisor.LongTermMemoryAdvisor;
import cn.jia.chat.advisor.RequestResponseAdvisor;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.memory.MemoryRepository;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.ShellTools;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springaicommunity.agent.tools.SmartWebFetchTool;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;
import io.modelcontextprotocol.client.McpSyncClient;

@Slf4j
@Configuration
public class ChatClientConfig {
    @Value("${chat.agent.skills.dirs:}")
    private String[] skillsRootDirectories;

    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder,
            List<McpSyncClient> mcpSyncClients, MemoryRepository memoryRepository,
            ChatMessageDao chatMessageDao) {
        // 使用 LongTermMemoryAdvisor 实现长效记忆检索(带会话权重)
        LongTermMemoryAdvisor longTermMemoryAdvisor = LongTermMemoryAdvisor.builder(memoryRepository)
                .memoryTopK(2)
                .similarityThreshold(0.75)
                .build();
        
        // 使用 DatabaseChatMemoryAdvisor 实现上下文记忆
        DatabaseChatMemoryAdvisor contextMemoryAdvisor = DatabaseChatMemoryAdvisor.builder(chatMessageDao)
                .maxMessages(10)
                .build();
        
        RequestResponseAdvisor chatControllerAdvisor = new RequestResponseAdvisor();
        // Configure Task tool with Claude sub-agents
        var taskTool = TaskTool.builder()
                .subagentTypes(ClaudeSubagentType.builder()
                        .chatClientBuilder("default", chatClientBuilder.clone())
                        .skillsDirectories(List.of(skillsRootDirectories))
                        .build())
                .build();
        return chatClientBuilder
                .defaultToolCallbacks(SyncMcpToolCallbackProvider.builder().mcpClients(mcpSyncClients).build())
                // Sub-Agents
                .defaultToolCallbacks(taskTool)
                .defaultToolCallbacks(SkillsTool.builder().addSkillsDirectories(List.of(skillsRootDirectories)).build())
                // Core Tools
                .defaultTools(
//                        GlobTool.builder().build(),
//                        FileSystemTools.builder().build(),
                        GrepTool.builder().build(),
                        ShellTools.builder().build(),
                        SmartWebFetchTool.builder(chatClientBuilder.clone().build()).build()
                )
                // Task orchestration
                .defaultTools(TodoWriteTool.builder().build())

                // User feedback tool (use CommandLineQuestionHandler for CLI apps)
//                .defaultTools(AskUserQuestionTool.builder()
//                        .questionHandler(new CommandLineQuestionHandler())
//                        .build())
                .defaultAdvisors(longTermMemoryAdvisor)
                .defaultAdvisors(contextMemoryAdvisor)
                .defaultAdvisors(SimpleLoggerAdvisor.builder().build())
                .defaultAdvisors(chatControllerAdvisor)
                .build();
    }
}
