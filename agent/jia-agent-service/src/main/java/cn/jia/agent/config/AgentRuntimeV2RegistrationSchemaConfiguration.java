package cn.jia.agent.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods=false)
public class AgentRuntimeV2RegistrationSchemaConfiguration {
    @Bean(name="agentRuntimeV2RegistrationSchemaGate",defaultCandidate=false)
    @ConditionalOnProperty(prefix="agent.runtime-v2-registration",name="enabled",havingValue="true")
    AgentRuntimeV2RegistrationSchemaGate gate(JdbcTemplate jdbc){return new AgentRuntimeV2RegistrationSchemaGate(jdbc);}
}
