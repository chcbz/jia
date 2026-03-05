package cn.jia.ai.mcp.client.config;

import java.util.List;

import cn.jia.ai.mcp.client.advisor.CustomerVectorStoreChatMemoryAdvisor;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.ShellTools;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springaicommunity.agent.tools.SmartWebFetchTool;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;
import io.modelcontextprotocol.client.McpSyncClient;
import cn.jia.ai.mcp.client.advisor.RequestResponseAdvisor;

@Slf4j
@Configuration
public class ChatClientConfig {
    @Value("${agent.skills.dirs}")
    private String[] skillsRootDirectories;

    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder, VectorStore vectorStore,
            List<McpSyncClient> mcpSyncClients) {
        QuestionAnswerAdvisor questionAnswerAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder().similarityThreshold(0.8d).topK(2).build()).build();
        CustomerVectorStoreChatMemoryAdvisor vectorStoreChatMemoryAdvisor =
                CustomerVectorStoreChatMemoryAdvisor.builder(vectorStore).defaultTopK(20).build();
        RequestResponseAdvisor chatControllerAdvisor = new RequestResponseAdvisor();
        // MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder().build();
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
                .defaultAdvisors(questionAnswerAdvisor)
//                .defaultAdvisors(ToolCallAdvisor.builder().disableMemory().build())
                .defaultAdvisors(vectorStoreChatMemoryAdvisor)
                .defaultAdvisors(SimpleLoggerAdvisor.builder().build())
                .defaultAdvisors(chatControllerAdvisor)
                .build();
    }
}
