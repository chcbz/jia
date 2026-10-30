package cn.jia.core.config;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One process scope for persistence and scheduled entry points, independent of HTTP identity. */
@Configuration(proxyBeanMethods = false)
public class ProcessTenantConfiguration {
    @Bean
    public FixedTenantAdmissionPolicy processTenantPolicy(
            @Value("${cyf.security.fixed-tenant.tenant-id}") String tenant,
            @Value("${cyf.security.fixed-tenant.client-id:}") String client) {
        return new FixedTenantAdmissionPolicy(tenant, client.isEmpty() ? null : client);
    }

}
