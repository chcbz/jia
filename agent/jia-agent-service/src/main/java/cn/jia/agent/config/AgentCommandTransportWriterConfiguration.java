package cn.jia.agent.config;

import cn.jia.agent.dao.AgentCommandTransportDao;
import cn.jia.agent.mapper.AgentCommandTransportMapper;
import cn.jia.agent.service.AgentCommandMailboxService;
import cn.jia.agent.service.AgentCommandTransportWriter;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import cn.jia.agent.service.impl.AgentCommandTransportWriterImpl;
import cn.jia.agent.service.impl.AgentCommandMailboxServiceImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class AgentCommandTransportWriterConfiguration {
    @Bean
    @Conditional(AgentCommandOutboxEnabledCondition.class)
    public AgentCommandMailboxService agentCommandMailboxService(
            AgentCommandTransportMapper mapper) {
        return new AgentCommandMailboxServiceImpl(mapper);
    }

    @Bean
    @Conditional(AgentCommandOutboxEnabledCondition.class)
    public AgentCommandTransportWriter agentCommandTransportWriter(
            AgentCommandTransportDao dao,
            AgentRabbitSafetyGate gate,
            AgentService agentService,
            AgentTaskCollaborationAccessService accessService,
            PlatformTransactionManager transactionManager) {
        return new AgentCommandTransportWriterImpl(
                dao, gate, agentService, accessService, transactionManager);
    }
}
