package cn.jia.chat.config;

import cn.jia.agent.config.*;
import cn.jia.agent.dao.AgentRuntimeV2RegistrationStore;
import cn.jia.agent.security.*;
import cn.jia.agent.service.*;
import cn.jia.chat.handler.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.util.*;
import java.util.function.BooleanSupplier;
import static cn.jia.chat.handler.AgentRuntimeV2Readiness.Dependency.*;

@Configuration(proxyBeanMethods=false)
public class AgentRuntimeV2WebSocketConfiguration {
    @Bean AgentRuntimeV2Readiness agentRuntimeV2Readiness(Environment environment,
            @Qualifier("agentRuntimeV2RegistrationSchemaGate") ObjectProvider<AgentRuntimeV2RegistrationSchemaGate> baseline,
            ObjectProvider<AgentRuntimeV2RegistrationDeltaSchemaGate> delta,ObjectProvider<AgentRuntimeV2RegistrationKeyRing> keys,
            ObjectProvider<AgentRuntimeV2RegistrationAuthorityResolver> resolver,ObjectProvider<AgentRuntimeV2RegistrationStore> store,
            ObjectProvider<AgentRuntimeV2RegistrationService> service,ObjectProvider<AgentRuntimeV2WebSocketAdapter> routing,
            ObjectProvider<AgentRuntimeV2CapabilityReadiness> capabilities) {
        Map<AgentRuntimeV2Readiness.Dependency,BooleanSupplier> evidence=new EnumMap<>(AgentRuntimeV2Readiness.Dependency.class);
        evidence.put(ACTIVATION,()->environment.getProperty("agent.runtime-v2-registration.activation-enabled",Boolean.class,false));
        evidence.put(BASELINE_SCHEMA,()->baseline.getIfAvailable()!=null&&delta.getIfAvailable()!=null&&delta.getIfAvailable().verified());
        evidence.put(DELTA_SCHEMA,()->delta.getIfAvailable()!=null&&delta.getIfAvailable().verified());
        evidence.put(KEYS,()->keys.getIfAvailable()!=null);evidence.put(RESOLVER,()->resolver.getIfAvailable()!=null);evidence.put(STORE,()->store.getIfAvailable()!=null);
        evidence.put(TRANSACTION_SERVICE,()->service.getIfAvailable() instanceof cn.jia.agent.service.impl.AgentRuntimeV2RegistrationServiceImpl);
        evidence.put(ROUTING,()->routing.getIfAvailable()!=null);evidence.put(POST_COMMIT_NETWORK,()->routing.getIfAvailable()!=null);
        for(var capability:AgentRuntimeV2CapabilityReadiness.Capability.values())evidence.put(AgentRuntimeV2Readiness.Dependency.valueOf(capability.name()),()->capabilities.orderedStream().filter(source->source.verifiedCapabilities().contains(capability)).count()==1);
        // R2c/R2d/Node/topology have no fallback evidence providers. A single flag cannot advertise V2.
        return new AgentRuntimeV2Readiness(evidence);
    }
}
