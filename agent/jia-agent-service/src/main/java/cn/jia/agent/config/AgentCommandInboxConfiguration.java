package cn.jia.agent.config;

import cn.jia.agent.dao.AgentCommandInboxDao;
import cn.jia.agent.service.AgentCommandInboxService;
import cn.jia.agent.service.impl.AgentCommandInboxServiceImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Conditional D07 wiring. Enabled consume with a missing DAO/transaction manager fails context startup. */
@Configuration(proxyBeanMethods = false)
public class AgentCommandInboxConfiguration {
    @Bean
    @Conditional(AgentRabbitConsumeEnabledCondition.class)
    public AgentCommandInboxService agentCommandInboxService(
            AgentCommandInboxDao dao,
            AgentRabbitSafetyGate gate,
            PlatformTransactionManager transactionManager) {
        return new AgentCommandInboxServiceImpl(dao, gate, transactionManager);
    }
}
