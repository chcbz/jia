package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/** Only creates an absent policy table. Grant migration belongs to versioned deployment. */
public final class AgentExternalServicePolicySchemaInitializer implements InitializingBean {
    private final JdbcTemplate jdbc;
    public AgentExternalServicePolicySchemaInitializer(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void afterPropertiesSet() throws Exception {
        var present=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='agent_external_service_policy'",Integer.class);
        if(present==null) throw new IllegalStateException("Policy catalog unavailable");
        if(present==0) jdbc.execute(new ClassPathResource("db/agent-external-service-policy-v1.sql").getContentAsString(StandardCharsets.UTF_8));
        var columns=Set.copyOf(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='agent_external_service_policy'",String.class));
        if(!columns.containsAll(List.of("tenant_id","client_id","provider_owner_jiacn","agent_id","binding_id","revision","expected_revision","enabled","idempotency_key","request_hash","created_at")))
            throw new IllegalStateException("External policy catalog is partial");
        String engine=jdbc.queryForObject("SELECT engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='agent_external_service_policy'",String.class);
        if(!"InnoDB".equalsIgnoreCase(engine)) throw new IllegalStateException("External policy requires InnoDB");
        var identity=jdbc.queryForList("SELECT column_name,is_nullable,collation_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='agent_external_service_policy' AND column_name IN ('tenant_id','client_id','provider_owner_jiacn','agent_id','idempotency_key')");
        if(identity.size()!=5 || identity.stream().anyMatch(row -> !"NO".equals(row.get("is_nullable")) || !"utf8mb4_0900_bin".equals(row.get("collation_name"))))
            throw new IllegalStateException("External policy identity collation/nullability drift");
        Integer nullable=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='agent_external_service_policy' AND is_nullable='YES'",Integer.class);
        if(nullable==null || nullable!=0) throw new IllegalStateException("External policy nullable fields");
        var checks=Set.copyOf(jdbc.queryForList("SELECT constraint_name FROM information_schema.table_constraints WHERE table_schema=DATABASE() AND table_name='agent_external_service_policy' AND constraint_type='CHECK' AND enforced='YES'",String.class));
        if(!checks.containsAll(List.of("chk_external_policy_revision","chk_external_policy_enabled","chk_external_policy_binding")))
            throw new IllegalStateException("External policy checks unavailable");
        for(var index:List.of("uk_external_policy_revision","uk_external_policy_request")) {
            var actual=jdbc.queryForList("SELECT column_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='agent_external_service_policy' AND index_name=? AND non_unique=0 ORDER BY seq_in_index",String.class,index);
            var expected=index.endsWith("revision")?List.of("tenant_id","client_id","agent_id","binding_id","revision")
                    :List.of("tenant_id","client_id","provider_owner_jiacn","idempotency_key");
            if(!expected.equals(actual)) throw new IllegalStateException("External policy unique index drift: "+index);
        }
    }
}
