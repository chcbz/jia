package cn.jia.test;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 内置RabbitMq服务器加载
 *
 * @author chc
 */
@Slf4j
@Testcontainers
public class RabbitMqServerLoader {
    @Value("${spring.rabbitmq.port:5672}")
    private int port;
    @Value("${spring.rabbitmq.username:test}")
    private String username;
    @Value("${spring.rabbitmq.password:test}")
    private String password;

    @Container
    public final RabbitMQContainer rabbitMQContainer = new RabbitMQContainer("rabbitmq:3.8")
            .withExposedPorts(port)
            .withEnv("RABBITMQ_DEFAULT_USER", username)
            .withEnv("RABBITMQ_DEFAULT_PASS", password);

}
