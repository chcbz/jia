package cn.jia.chat.config;

import java.util.List;

import cn.jia.chat.memory.DatabaseChatMemoryRepository;
import cn.jia.kefu.service.KefuChatMessageService;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.ShellTools;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springaicommunity.agent.tools.SmartWebFetchTool;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;
import io.modelcontextprotocol.client.McpSyncClient;
import cn.jia.chat.advisor.RequestResponseAdvisor;

@Slf4j
@Configuration
public class ChatClientConfig {
    @Value("${chat.skills.dirs}")
    private String[] skillsRootDirectories;

    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder,
            List<McpSyncClient> mcpSyncClients, VectorStore vectorStore,
            KefuChatMessageService kefuChatMessageService) {
        // 使用 QuestionAnswerAdvisor + 自动配置的 ElasticsearchVectorStore 实现长效记忆检索(基于向量相似度)
        QuestionAnswerAdvisor longTermMemoryAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .similarityThreshold(0.8d)
                        .topK(5)
                        .build())
                .build();
        
        // 使用 DatabaseChatMemoryRepository 实现上下文记忆(替代 CustomerVectorStoreChatMemoryAdvisor)
        DatabaseChatMemoryRepository dbChatMemoryRepository = DatabaseChatMemoryRepository.builder()
                .kefuChatMessageService(kefuChatMessageService)
                .build();
        ChatMemory contextChatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(dbChatMemoryRepository)
                .maxMessages(10)
                .build();
        MessageChatMemoryAdvisor contextMemoryAdvisor = MessageChatMemoryAdvisor.builder(contextChatMemory).build();
        
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
