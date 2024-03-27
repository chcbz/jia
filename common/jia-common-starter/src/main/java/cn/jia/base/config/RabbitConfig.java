package cn.jia.base.config;


import org.springframework.amqp.core.Queue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
@ConditionalOnClass(Queue.class)
public class RabbitConfig {
	@Bean
    public Queue queue() {
        return new Queue("jia.sms");
    }
}
