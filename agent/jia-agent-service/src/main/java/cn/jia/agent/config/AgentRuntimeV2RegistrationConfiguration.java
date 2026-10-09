package cn.jia.agent.config;

import cn.jia.agent.dao.AgentRuntimeV2RegistrationStore;
import cn.jia.agent.security.*;
import cn.jia.agent.service.AgentRuntimeV2RegistrationService;
import cn.jia.agent.service.impl.AgentRuntimeV2RegistrationServiceImpl;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.util.*;

/** Explicit dormant wiring. No DDL/control writes, no registration-only protocol advertisement. */
@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(AgentRuntimeV2RegistrationProperties.class)
@ConditionalOnProperty(prefix="agent.runtime-v2-registration",name="enabled",havingValue="true")
public class AgentRuntimeV2RegistrationConfiguration {
    @Bean AgentRuntimeV2RegistrationDeltaSchemaGate agentRuntimeV2RegistrationDeltaSchemaGate(JdbcTemplate jdbc,
            @Qualifier("agentRuntimeV2RegistrationSchemaGate") AgentRuntimeV2RegistrationSchemaGate baseline) {
        return new AgentRuntimeV2RegistrationDeltaSchemaGate(jdbc,baseline);
    }
    @Bean AgentRuntimeV2RegistrationKeyRing agentRuntimeV2RegistrationKeyRing(JdbcTemplate jdbc,
            AgentRuntimeV2RegistrationDeltaSchemaGate schema,AgentRuntimeV2RegistrationProperties properties) {
        if(!schema.verified())throw new IllegalStateException("registration schema not verified");
        Map<String,SecretKey> ring=new HashMap<>();
        properties.getHmacKeys().forEach((id,value)->{byte[] bytes=Base64.getDecoder().decode(value);if(!Base64.getEncoder().encodeToString(bytes).equals(value))throw new IllegalArgumentException("noncanonical registration key encoding");ring.put(id,new SecretKeySpec(bytes,"HmacSHA256"));Arrays.fill(bytes,(byte)0);});
        Set<String> references=new HashSet<>(jdbc.queryForList("""
            SELECT credential_key_id FROM agent_runtime_v2_registration_authority WHERE status='ACTIVE'
            UNION SELECT verifier_key_id FROM agent_runtime_v2_binding WHERE contract_version=2 AND status<>'REVOKED'
            UNION SELECT challenge_digest_key_id FROM agent_runtime_v2_reconnect_challenge WHERE status='ISSUED'
            """,String.class));
        return new AgentRuntimeV2RegistrationKeyRing(properties.getActiveKeyId(),ring,references);
    }
    @Bean AgentRuntimeV2RegistrationStore agentRuntimeV2RegistrationStore(JdbcTemplate jdbc,AgentRuntimeV2RegistrationDeltaSchemaGate schema){return new AgentRuntimeV2RegistrationStore(jdbc);}
    @Bean AgentRuntimeV2RegistrationAuthorityResolver agentRuntimeV2RegistrationAuthorityResolver(AgentRuntimeV2RegistrationStore db,AgentRuntimeV2RegistrationKeyRing keys){return new AgentRuntimeV2RegistrationAuthorityResolver(db,keys,Clock.systemUTC());}
    @Bean AgentRuntimeV2RegistrationService agentRuntimeV2RegistrationService(AgentRuntimeV2RegistrationStore db,AgentRuntimeV2RegistrationAuthorityResolver resolver,AgentRuntimeV2RegistrationKeyRing keys){return new AgentRuntimeV2RegistrationServiceImpl(db,resolver,keys,Clock.systemUTC());}
}
