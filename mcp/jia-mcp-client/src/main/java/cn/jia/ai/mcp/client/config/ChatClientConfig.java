package cn.jia.ai.mcp.client.config;

import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.VectorStoreChatMemoryAdvisor;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;
import io.modelcontextprotocol.client.McpSyncClient;
import cn.jia.ai.mcp.client.advisor.RequestResponseAdvisor;

@Slf4j
@Configuration
public class ChatClientConfig {
    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder, VectorStore vectorStore,
            List<McpSyncClient> mcpSyncClients) {
        ToolCallbackProvider toolCallbacks = new SyncMcpToolCallbackProvider(mcpSyncClients);
        QuestionAnswerAdvisor questionAnswerAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder().similarityThreshold(0.8d).topK(2).build()).build();
        VectorStoreChatMemoryAdvisor vectorStoreChatMemoryAdvisor = VectorStoreChatMemoryAdvisor.builder(vectorStore)
                .defaultTopK(20).build();
        RequestResponseAdvisor chatControllerAdvisor = new RequestResponseAdvisor();
        // MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder().build();

        return chatClientBuilder
                .defaultToolCallbacks(toolCallbacks)
                .defaultAdvisors(questionAnswerAdvisor)
                .defaultAdvisors(vectorStoreChatMemoryAdvisor)
                .defaultAdvisors(SimpleLoggerAdvisor.builder().build())
                .defaultAdvisors(chatControllerAdvisor)
                .build();
    }
}
