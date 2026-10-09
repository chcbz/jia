package cn.jia.agent.config;

import cn.jia.agent.dao.*;
import cn.jia.agent.dao.impl.AgentRuntimeV2InternalOutboxDaoImpl;
import cn.jia.agent.entity.AgentRuntimeV2CompositeWorkerProof;
import cn.jia.agent.mapper.AgentRuntimeV2InternalOutboxMapper;
import cn.jia.agent.security.*;
import cn.jia.agent.service.*;
import cn.jia.agent.service.impl.*;
import cn.jia.core.tenant.WorkerScope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;

/** Dormant/manual-DDL integration. Scheduler remains disabled and full readiness-gated by default. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="agent.runtime-v2-dispatch",name="enabled",havingValue="true")
public class AgentRuntimeV2DispatchConfiguration {
    @Bean AgentRuntimeV2DispatchSchemaGate agentRuntimeV2DispatchSchemaGate(JdbcTemplate jdbc,AgentRuntimeV2RegistrationDeltaSchemaGate baseline){return new AgentRuntimeV2DispatchSchemaGate(jdbc,baseline);}
    @Bean AgentRuntimeV2InternalOutboxDao agentRuntimeV2InternalOutboxDao(JdbcTemplate jdbc,AgentRuntimeV2DispatchSchemaGate gate){if(!gate.verified())throw new IllegalStateException("dispatch schema not verified");return new AgentRuntimeV2InternalOutboxDaoImpl(new AgentRuntimeV2InternalOutboxMapper(jdbc));}
    @Bean AgentRuntimeV2CompositeAuthority agentRuntimeV2CompositeAuthority(AgentRuntimeV2RegistrationStore store,AgentRuntimeV2RegistrationKeyRing keys){return new AgentRuntimeV2CompositeAuthority(store,keys,Clock.systemUTC());}
    @Bean AgentRuntimeV2CompositeWorkerProof agentRuntimeV2CompositeWorkerProof(@Qualifier("agentOutboxWorkerScope") WorkerScope scope,@Qualifier("agentOutboxProductionRuntimeArtifact") AgentOutboxWorkerAuthorityConfiguration.RuntimeArtifact artifact,Environment environment){return AgentRuntimeV2CompositeWorkerProof.from(scope,artifact.sha256(),environment.getRequiredProperty("cyf.deployment.registration-credential"));}
    @Bean AgentRuntimeV2DispatchSource agentRuntimeV2DispatchSource(AgentRuntimeV2InternalOutboxDao dao,ObjectProvider<AgentRuntimeV2DispatchSource.ContinuationVerifier> continuation){var verifier=continuation.getIfAvailable();return verifier==null?new AgentRuntimeV2DispatchSource(dao):new AgentRuntimeV2DispatchSource(dao,verifier);}
    @Bean(name="agentRuntimeV2DispatchService",defaultCandidate=false) AgentRuntimeV2DispatchService dispatch(AgentRuntimeV2InternalOutboxDao dao,AgentRuntimeV2CompositeAuthority authority,AgentRuntimeV2CompositeWorkerProof process,PlatformTransactionManager tx,AgentRuntimeV2DispatchSource source){return new AgentRuntimeV2DispatchServiceImpl(dao,authority,process,tx,Clock.systemUTC(),source,failure->{});}
    @Bean AgentRuntimeV2InternalOutboxRelay agentRuntimeV2InternalOutboxRelay(AgentRuntimeV2InternalOutboxDao dao,AgentRuntimeV2CompositeAuthority authority,@Qualifier("agentRuntimeV2SocketGateway") AgentRuntimeV2SocketGateway gateway,PlatformTransactionManager tx,AgentRuntimeV2DispatchSource source){return new AgentRuntimeV2InternalOutboxRelay(dao,authority,gateway,tx,Clock.systemUTC(),source);}
    // Capability evidence is deliberately a default candidate for both readiness aggregators;
    // the dispatch service itself stays named/non-default to isolate legacy execution wiring.
    @Bean(name="agentRuntimeV2DispatchCapability") AgentRuntimeV2CapabilityReadiness dispatchCapability(AgentRuntimeV2DispatchSchemaGate gate,@Qualifier("agentRuntimeV2DispatchService") AgentRuntimeV2DispatchService dispatch,AgentRuntimeV2InternalOutboxRelay relay,@Qualifier("agentRuntimeV2SocketGateway") AgentRuntimeV2SocketGateway gateway){return ()->gate.verified()?java.util.Set.of(AgentRuntimeV2CapabilityReadiness.Capability.RECEIVER_DISPATCH):java.util.Set.of();}
    @Bean AgentRuntimeV2DispatchScheduler agentRuntimeV2DispatchScheduler(Environment environment,AgentRuntimeV2InternalOutboxRelay relay,ObjectProvider<AgentRuntimeV2CapabilityReadiness> capabilities){return new AgentRuntimeV2DispatchScheduler(environment,relay,capabilities);}
    /** Dispatch evidence alone cannot activate sending or supply execution/Node/topology evidence. */
    public static final class AgentRuntimeV2DispatchScheduler {
        private final Environment environment;private final AgentRuntimeV2InternalOutboxRelay relay;private final ObjectProvider<AgentRuntimeV2CapabilityReadiness> capabilities;
        AgentRuntimeV2DispatchScheduler(Environment environment,AgentRuntimeV2InternalOutboxRelay relay,ObjectProvider<AgentRuntimeV2CapabilityReadiness> capabilities){this.environment=environment;this.relay=relay;this.capabilities=capabilities;}
        @Scheduled(fixedDelay=1000) public void poll(){if(!environment.getProperty("agent.runtime-v2-dispatch.scheduler-enabled",Boolean.class,false)||!environment.getProperty("agent.runtime-v2-registration.activation-enabled",Boolean.class,false))return;for(var capability:AgentRuntimeV2CapabilityReadiness.Capability.values())if(capabilities.orderedStream().filter(c->c.verifiedCapabilities().contains(capability)).count()!=1)return;relay.poll(100);}
    }
}
