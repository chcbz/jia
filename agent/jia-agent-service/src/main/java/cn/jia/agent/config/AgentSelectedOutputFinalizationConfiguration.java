package cn.jia.agent.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods=false)
public class AgentSelectedOutputFinalizationConfiguration {
    @Bean
    @DependsOn("agentTaskFormalDeliverySchemaInitializer")
    @ConditionalOnProperty(prefix="jia.agent.selected-output-finalization",name="enabled",havingValue="true")
    public AgentSelectedOutputFinalizationSchemaInitializer agentSelectedOutputFinalizationSchemaInitializer(JdbcTemplate jdbc) {
        return new AgentSelectedOutputFinalizationSchemaInitializer(jdbc);
    }
}
