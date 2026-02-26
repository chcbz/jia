package cn.jia.ai.mcp.client.config;

import java.util.List;

import cn.jia.ai.mcp.client.advisor.CustomerVectorStoreChatMemoryAdvisor;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.ShellTools;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springaicommunity.agent.tools.TodoWriteTool;
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

        return chatClientBuilder
                .defaultToolCallbacks(SyncMcpToolCallbackProvider.builder().mcpClients(mcpSyncClients).build())
                .defaultToolCallbacks(SkillsTool.builder().addSkillsDirectories(List.of(skillsRootDirectories)).build())
                .defaultTools(
//                        GlobTool.builder().build(),
//                        FileSystemTools.builder().build(),
                        GrepTool.builder().build(),
                        TodoWriteTool.builder().build(),
                        ShellTools.builder().build()
                )
                .defaultAdvisors(questionAnswerAdvisor)
                .defaultAdvisors(vectorStoreChatMemoryAdvisor)
                .defaultAdvisors(SimpleLoggerAdvisor.builder().build())
                .defaultAdvisors(chatControllerAdvisor)
                .build();
    }
}
