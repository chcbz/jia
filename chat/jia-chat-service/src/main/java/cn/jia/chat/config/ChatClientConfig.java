package cn.jia.chat.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import cn.jia.chat.advisor.DatabaseChatMemoryAdvisor;
import cn.jia.chat.advisor.LongTermMemoryAdvisor;
import cn.jia.chat.advisor.RequestResponseAdvisor;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.memory.MemoryRepository;
import cn.jia.chat.tool.AgentTools;
import cn.jia.chat.tool.ChatTools;
import cn.jia.chat.tool.KefuTools;
import cn.jia.chat.tool.MaterialTools;
import cn.jia.chat.tool.PointTools;
import cn.jia.chat.tool.TaskTools;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.ShellTools;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springaicommunity.agent.tools.SmartWebFetchTool;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import lombok.extern.slf4j.Slf4j;
import io.modelcontextprotocol.client.McpSyncClient;

@Slf4j
@Configuration
public class ChatClientConfig {
    @Value("${chat.agent.skills.dirs:}")
    private String[] skillsRootDirectories;

    @Value("${spring.ai.mcp.client.toolcallback.enabled:true}")
    private boolean mcpToolCallbacksEnabled;

    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder,
            ObjectProvider<McpSyncClient> mcpSyncClientsProvider, MemoryRepository memoryRepository,
            ChatMessageDao chatMessageDao, TaskTools taskTools,
            @Lazy ObjectProvider<AgentTools> agentToolsProvider,
            @Lazy ObjectProvider<PointTools> pointToolsProvider,
            @Lazy ObjectProvider<MaterialTools> materialToolsProvider,
            @Lazy ObjectProvider<KefuTools> kefuToolsProvider,
            @Lazy ObjectProvider<ChatTools> chatToolsProvider) {
        ToolCallback[] taskToolCallbacks = ToolCallbacks.from(taskTools);
        List<McpSyncClient> mcpSyncClients = mcpSyncClientsProvider.orderedStream().toList();
        LongTermMemoryAdvisor longTermMemoryAdvisor = LongTermMemoryAdvisor.builder(memoryRepository)
                .memoryTopK(2)
                .similarityThreshold(0.75)
                .build();

        DatabaseChatMemoryAdvisor contextMemoryAdvisor = DatabaseChatMemoryAdvisor.builder(chatMessageDao)
                .maxMessages(10)
                .build();

        RequestResponseAdvisor chatControllerAdvisor = new RequestResponseAdvisor();
        List<String> skillsDirectories = configuredSkillsDirectories();
        var taskTool = TaskTool.builder()
                .subagentTypes(ClaudeSubagentType.builder()
                        .chatClientBuilder("default", chatClientBuilder.clone())
                        .skillsDirectories(skillsDirectories)
                        .build())
                .build();
        ChatClient.Builder builder = chatClientBuilder;
        if (mcpToolCallbacksEnabled && !mcpSyncClients.isEmpty()) {
            ToolCallback[] mcpToolCallbacks = SyncMcpToolCallbackProvider.builder()
                    .mcpClients(mcpSyncClients)
                    .build()
                    .getToolCallbacks();
            builder = builder.defaultToolCallbacks(mcpToolCallbacks);
        } else {
            log.info("MCP tool callbacks are disabled for ChatClient");
        }
        builder = builder.defaultTools(taskTool);
        builder = builder.defaultTools((Object[]) taskToolCallbacks);
        if (!skillsDirectories.isEmpty()) {
            builder = builder.defaultToolCallbacks(SkillsTool.builder().addSkillsDirectories(skillsDirectories).build());
        } else {
            log.warn("No valid skill directories configured; SkillsTool is disabled for ChatClient");
        }
        AgentTools agentTools = agentToolsProvider.getIfAvailable();
        if (agentTools != null) {
            builder = builder.defaultTools(ToolCallbacks.from(agentTools));
        }
        PointTools pointTools = pointToolsProvider.getIfAvailable();
        if (pointTools != null) {
            builder = builder.defaultTools(ToolCallbacks.from(pointTools));
        }
        MaterialTools materialTools = materialToolsProvider.getIfAvailable();
        if (materialTools != null) {
            builder = builder.defaultTools(ToolCallbacks.from(materialTools));
        }
        KefuTools kefuTools = kefuToolsProvider.getIfAvailable();
        if (kefuTools != null) {
            builder = builder.defaultTools(ToolCallbacks.from(kefuTools));
        }
        ChatTools chatTools = chatToolsProvider.getIfAvailable();
        if (chatTools != null) {
            builder = builder.defaultTools(ToolCallbacks.from(chatTools));
        }
        return builder
                .defaultTools(
                        GrepTool.builder().build(),
                        ShellTools.builder().build(),
                        SmartWebFetchTool.builder(chatClientBuilder.clone().build()).build()
                )
                .defaultTools(TodoWriteTool.builder().build())
                .defaultAdvisors(longTermMemoryAdvisor)
                .defaultAdvisors(contextMemoryAdvisor)
                .defaultAdvisors(SimpleLoggerAdvisor.builder().build())
                .defaultAdvisors(chatControllerAdvisor)
                .build();
    }

    private List<String> configuredSkillsDirectories() {
        return Arrays.stream(skillsRootDirectories)
                .map(String::trim)
                .filter(directory -> !directory.isEmpty())
                .filter(directory -> {
                    boolean exists = Files.isDirectory(Path.of(directory));
                    if (!exists) {
                        log.warn("Configured skill directory does not exist: {}", directory);
                    }
                    return exists;
                })
                .toList();
    }
}