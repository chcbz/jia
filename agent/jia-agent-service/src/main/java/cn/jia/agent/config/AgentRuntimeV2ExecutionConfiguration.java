package cn.jia.agent.config;

import cn.jia.agent.dao.*;
import cn.jia.agent.dao.impl.AgentRuntimeV2ExecutionStoreImpl;
import cn.jia.agent.mapper.AgentRuntimeV2ExecutionMapper;
import cn.jia.agent.security.*;
import cn.jia.agent.service.*;
import cn.jia.agent.service.impl.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;

/** Explicit dormant R2d wiring. The capability is discoverable but supplies no future Node/topology evidence. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="agent.runtime-v2-execution",name="enabled",havingValue="true")
public class AgentRuntimeV2ExecutionConfiguration {
    @Bean AgentRuntimeV2ExecutionSchemaGate agentRuntimeV2ExecutionSchemaGate(JdbcTemplate jdbc,AgentRuntimeV2DispatchSchemaGate baseline){return new AgentRuntimeV2ExecutionSchemaGate(jdbc,baseline);}
    @Bean AgentRuntimeV2ExecutionStore agentRuntimeV2ExecutionStore(JdbcTemplate jdbc,AgentRuntimeV2ExecutionSchemaGate gate){if(!gate.verified())throw new IllegalStateException("execution schema not verified");return new AgentRuntimeV2ExecutionStoreImpl(new AgentRuntimeV2ExecutionMapper(jdbc));}
    @Bean AgentRuntimeV2ExecutionEvidenceValidator agentRuntimeV2ExecutionEvidenceValidator(AgentRuntimeV2ExecutionStore store){return new AgentRuntimeV2ExecutionEvidenceValidator(store);}
    @Bean AgentRuntimeV2ExecutionAuthority agentRuntimeV2ExecutionAuthority(AgentRuntimeV2RegistrationStore store,AgentRuntimeV2RegistrationKeyRing keys){return new AgentRuntimeV2ExecutionAuthority(store,keys,Clock.systemUTC());}
    @Bean AgentRuntimeV2ExecutionService agentRuntimeV2ExecutionService(AgentRuntimeV2ExecutionStore store,AgentRuntimeV2ExecutionAuthority authority,AgentRuntimeV2ExecutionEvidenceValidator validator,PlatformTransactionManager tx){return new AgentRuntimeV2ExecutionServiceImpl(store,authority,validator,tx,Clock.systemUTC());}
    @Bean(name="agentRuntimeV2ExecutionCapability") AgentRuntimeV2CapabilityReadiness executionCapability(AgentRuntimeV2ExecutionSchemaGate gate,AgentRuntimeV2ExecutionService service,AgentRuntimeV2ExecutionEvidenceValidator validator){return ()->gate.verified()?java.util.Set.of(AgentRuntimeV2CapabilityReadiness.Capability.EXECUTION_HOLDER_TRANSFER):java.util.Set.of();}
}
