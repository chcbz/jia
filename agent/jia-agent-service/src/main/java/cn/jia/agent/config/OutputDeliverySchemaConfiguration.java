package cn.jia.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class OutputDeliverySchemaConfiguration {
    @Bean
    @Conditional(OutputDeliveryEnabledCondition.class)
    public OutputDeliverySchemaInitializer outputDeliverySchemaInitializer(
            JdbcTemplate jdbcTemplate) {
        return new OutputDeliverySchemaInitializer(jdbcTemplate);
    }
}
