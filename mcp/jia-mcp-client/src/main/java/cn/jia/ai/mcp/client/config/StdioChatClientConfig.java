package cn.jia.ai.mcp.client.config;

import java.util.Scanner;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "jia.mcp.client.stdio.enable", havingValue = "true")
public class StdioChatClientConfig {
    @Bean
    public CommandLineRunner predefinedQuestions(ChatClient chatClient, ConfigurableApplicationContext context) {
        return args -> {
            try (Scanner scanner = new Scanner(System.in)) {
                while (true) {
                    log.info("请输入问题，输入exit退出。");
                    String question = scanner.nextLine();
                    if (question.equals("exit")) {
                        break;
                    }
                    try {
                        // Prompt prompt = Prompt.builder().messages(
                        //         UserMessage.builder().text(question).build()
                        //     ).build();
                        Flux<String> responseFlux = chatClient.prompt(question)
                            .stream()
                            .content();
                        responseFlux.subscribe(content -> log.info("ASSISTANT: {}", content));
                    } catch (Exception e) {
                        log.error("Error occurred while processing the question: {}", question, e);
                        continue;
                    }
                }
            }
            context.close();
        };
    }
}
