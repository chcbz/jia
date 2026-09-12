package cn.jia.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class OutputDeliverySchemaConfiguration {
    @Bean
    @Conditional(OutputDeliveryEnabledCondition.class)
    public OutputDeliverySchemaInitializer outputDeliverySchemaInitializer(
            JdbcTemplate jdbcTemplate) {
        return new OutputDeliverySchemaInitializer(jdbcTemplate);
    }

    @Bean
    @Conditional(OutputDeliveryEnabledCondition.class)
    public OutputObjectSchemaInitializer outputObjectSchemaInitializer(JdbcTemplate jdbcTemplate) {
        return new OutputObjectSchemaInitializer(jdbcTemplate);
    }

    @Bean
    @Conditional(OutputDeliveryEnabledCondition.class)
    @DependsOn("agentSchemaInitializer")
    public OutputDeliveryLeaseSchemaInitializer outputDeliveryLeaseSchemaInitializer(
            JdbcTemplate jdbcTemplate) {
        return new OutputDeliveryLeaseSchemaInitializer(jdbcTemplate);
    }
}
