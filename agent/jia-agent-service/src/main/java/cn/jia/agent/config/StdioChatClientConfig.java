package cn.jia.agent.config;

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
@ConditionalOnProperty(name = "jia.agent.service.stdio.enable", havingValue = "true")
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
                        
                        // 使用blockLast()等待流完成，而不是直接subscribe
                        responseFlux.doOnNext(content -> log.info("ASSISTANT: {}", content))
                                   .blockLast(); // 在阻塞式命令行环境中使用blockLast是可以接受的
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
